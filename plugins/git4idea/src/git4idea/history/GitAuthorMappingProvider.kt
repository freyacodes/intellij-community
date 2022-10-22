// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.history

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.AsyncFileListener.ChangeApplier
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.addIfNotNull
import com.intellij.util.text.nullize
import com.intellij.vcs.log.VcsUser
import com.intellij.vcs.log.impl.VcsUserImpl
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks .mailmap and provides access to its mappings
 *
 * Based on https://git-scm.com/docs/gitmailmap
 *
 * @author Freya Arbjerg
 */
@Service
class GitAuthorMappingProvider(private val project: Project) : Disposable {

  private val rootsAndMappings: ConcurrentHashMap<VirtualFile, Mappings> = ConcurrentHashMap()

  init {
    project.messageBus.connect(this).subscribe(VCS_REPOSITORY_MAPPING_UPDATED, RepositoryListener())
    onRepositoryUpdate(GitRepositoryManager.getInstance(project).repositories)
    VirtualFileManager.getInstance().addAsyncFileListener(FileListener(), this)
  }

  /** Note: The priorities of mailmap declarations are undefined by Git documentation */
  private data class Mappings(
    // Proper Name <commit@email.xx>
    val emailToNameMappings: Map<String, String> = emptyMap(),
    // <proper@email.xx> <commit@email.xx>
    val emailToEmailMappings: Map<String, String> = emptyMap(),
    // Proper Name <proper@email.xx> <commit@email.xx>
    val emailToUserMappings: Map<String, VcsUser> = emptyMap(),
    // Proper Name <proper@email.xx> Commit Name <commit@email.xx>
    val userToUserMappings: Map<VcsUser, VcsUser> = emptyMap()
  )

  /**
   * @return whether this provider is ready to provide mappings
   */
  fun isReady(vcsRoot: VirtualFile): Boolean {
    return rootsAndMappings.containsKey(vcsRoot)
  }

  /**
   * @return the overriding name and email, or null if no match is found
   */
  operator fun get(vcsRoot: VirtualFile, user: VcsUser): VcsUser? {
    // Note: Git documentation does not define the precedence of statements
    val mappings = rootsAndMappings[vcsRoot] ?: return null
    mappings.userToUserMappings[user.lowercase()]?.let { return it }
    mappings.emailToUserMappings[user.email]?.let { return it }
    mappings.emailToNameMappings[user.email]?.let { return VcsUserImpl(it, user.email) }
    mappings.emailToEmailMappings[user.email]?.let { return VcsUserImpl(user.name, it) }
    TODO()
  }

  private fun onRepositoryUpdate(repositories: List<GitRepository>) {
    val roots = repositories.map { it.root }

    // Clean up removed repositories
    rootsAndMappings.keys.toMutableList()
      .apply { removeAll(roots) }
      .forEach { rootsAndMappings.remove(it) }

    roots.filterNot { rootsAndMappings.containsKey(it) }
      .forEach { checkRootAsync(it) }
  }

  private fun checkRootAsync(root: VirtualFile) {
    ReadAction.nonBlocking<Mappings> { parseMailmap(root.findChild(MAILMAP_FILE_NAME)) }
      .submit(AppExecutorUtil.getAppExecutorService())
      .then { rootsAndMappings[root] = it }
  }

  private fun parseMailmap(file: VirtualFile?): Mappings {
    file ?: return EMPTY_MAPPINGS
    if (!file.isValid) return EMPTY_MAPPINGS

    // Proper Name <commit@email.xx>
    val emailToNameMappings = mutableMapOf<String, String>()
    // <proper@email.xx> <commit@email.xx>
    val emailToEmailMappings = mutableMapOf<String, String>()
    // Proper Name <proper@email.xx> <commit@email.xx>
    val emailToUser = mutableMapOf<String, VcsUser>()
    // Proper Name <proper@email.xx> Commit Name <commit@email.xx>
    val userToUserMappings = mutableMapOf<VcsUser, VcsUser>()

    String(file.contentsToByteArray(), file.charset).lines().forEach { line ->
      val tokens = tokenize(line)
      when (tokens.size) {
        2 -> {
          val (new, oldEmail) = tokens
          if (!oldEmail.isEmail) return@forEach
          if (new.isEmail) {
            emailToEmailMappings[oldEmail.string.lowercase()] = new.string
          } else {
            emailToNameMappings[oldEmail.string.lowercase()] = new.string
          }
        }
        3 -> {
          val (newName, newEmail, oldEmail) = tokens
          if (newName.isEmail || !newEmail.isEmail || !oldEmail.isEmail) return@forEach
          emailToUser[oldEmail.string.lowercase()] = VcsUserImpl(newName.string, newEmail.string)
        }
        4 -> {
          val (newName, newEmail, oldName, oldEmail) = tokens
          if (newName.isEmail || !newEmail.isEmail || oldName.isEmail || !oldEmail.isEmail) return@forEach
          userToUserMappings[VcsUserImpl(oldName.string.lowercase(), oldEmail.string)] = VcsUserImpl(newName.string, newEmail.string)
        }
        else -> {}
      }
    }

    return Mappings(emailToNameMappings, emailToEmailMappings, emailToUser, userToUserMappings)
  }

  private fun tokenize(line: String): List<Token> {
    val tokens = mutableListOf<Token>()
    val currentToken = StringBuilder()
    var isEmail = false
    line.trim().forEach { next ->
      if (next == '#') {
        currentToken.toString().nullize(true)?.let { tokens.add(Token(it, isEmail)) }
        return tokens
      }

      if (currentToken.isEmpty()) {
        if (next.isWhitespace()) return@forEach
        isEmail = next == '<'
        if (!isEmail) currentToken.append(next)
      } else if (isEmail){
        if (next == '>') {
          currentToken.toString().nullize(true)?.let { tokens.add(Token(it, true)) }
          currentToken.clear()
        } else {
          currentToken.append(next)
        }
      } else {
        if (next == '<') {
          currentToken.toString().nullize(true)?.let { tokens.add(Token(it, false)) }
          currentToken.clear()
        } else {
          currentToken.append(next)
        }
      }
    }
    currentToken.toString().nullize(true)?.let { tokens.add(Token(it, isEmail)) }
    return tokens
  }

  private data class Token(val string: String, val isEmail: Boolean)

  // Email is already lowercased in constructor
  private fun VcsUser.lowercase(): VcsUser = VcsUserImpl(name.lowercase(), email)

  private inner class RepositoryListener : VcsRepositoryMappingListener {
    override fun mappingChanged() {
      onRepositoryUpdate(VcsRepositoryManager.getInstance(project).repositories.filterIsInstance(GitRepository::class.java))
    }
  }

  private inner class FileListener : AsyncFileListener {
    private fun getAffectedRoot(path: String) = rootsAndMappings.keys.find { "${it.path}/$MAILMAP_FILE_NAME" == path }

    override fun prepareChange(events: List<VFileEvent>): ChangeApplier? {
      val affectedRoots = mutableListOf<VirtualFile>()

      events.forEach { event ->
        if (event is VFilePropertyChangeEvent) return null
        var affectedRoot = getAffectedRoot(event.path)

        if (affectedRoot == null && event is VFileMoveEvent) {
          affectedRoot = getAffectedRoot(event.newPath)
        } else if (affectedRoot == null && event is VFileCopyEvent) {
          affectedRoot = getAffectedRoot(event.newParent.path + "/" + event.newChildName)
        }
        affectedRoots.addIfNotNull(affectedRoot)
      }

      if (affectedRoots.isEmpty()) return null
      return object : ChangeApplier {
        override fun afterVfsChange() {
          affectedRoots.forEach { checkRootAsync(it) }
        }
      }
    }
  }

  override fun dispose() {}

  companion object {
    private val EMPTY_MAPPINGS = Mappings()
    private const val MAILMAP_FILE_NAME = ".mailmap"
  }
}