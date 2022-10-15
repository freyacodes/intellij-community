// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.history

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.history.AuthorMappingProvider
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
 * @author Freya Arbjerg
 */
@Service
class GitAuthorMappingProvider(private val project: Project) : AuthorMappingProvider, Disposable {

  private val rootsAndMappings: ConcurrentHashMap<VirtualFile, Mappings> = ConcurrentHashMap()

  init {
    project.messageBus.connect(this).subscribe(VCS_REPOSITORY_MAPPING_UPDATED, RepositoryListener())
    onRepositoryUpdate(GitRepositoryManager.getInstance(project).repositories)
    VirtualFileManager.getInstance().addAsyncFileListener(FileListener(), this)
  }

  /** Note: The priorities of mailmap declarations are undefined by Git documentation */
  private class Mappings(
    val emailToEmailMappings: Map<String, String> = emptyMap(),
    val userToEmailMappings: Map<VcsUser, String> = emptyMap(),
    val userToUserMappings: Map<VcsUser, VcsUser> = emptyMap()
  )

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

  override fun isReady(vcsRoot: VirtualFile): Boolean {
    return rootsAndMappings.containsKey(vcsRoot)
  }

  override operator fun get(vcsRoot: VirtualFile, user: VcsUser): VcsUser? {
    val mappings = rootsAndMappings[vcsRoot] ?: return null
    mappings.userToUserMappings[user]?.let { return it }
    val email = mappings.userToEmailMappings[user] ?: mappings.emailToEmailMappings[user.email]
    if (email != null) return VcsUserImpl(user.name, email)
    return user
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

    String(file.contentsToByteArray(), file.charset).lines().forEach {
      val tokens = tokenize(it)
    }

    return Mappings(
      userToUserMappings = mapOf(VcsUserImpl("john doe", "john@doe.com") to VcsUserImpl("Freya Arbjerg", "freya@arbjerg.dev")))
  }

  private fun tokenize(line: String): List<String> {
    val tokens = mutableListOf<String>()
    var currentToken = StringBuilder()
    var isEmail = false
    line.trim().forEach { next ->
      if (next == '#') {
        tokens.addIfNotNull(currentToken.toString().nullize(true))
        return tokens
      }

      if (currentToken.isEmpty()) {
        if (next.isWhitespace()) return@forEach
        isEmail = next == '<'
        if (!isEmail) currentToken.append(next)
      } else if (isEmail){
        if (next == '>') {
          tokens.addIfNotNull(currentToken.toString().nullize(true))
          currentToken = StringBuilder()
        } else {
          currentToken.append(next)
        }
      } else {
        if (next == '<') {
          tokens.addIfNotNull(currentToken.toString().nullize(true))
          currentToken = StringBuilder()
        } else {
          currentToken.append(next)
        }
      }
    }
    tokens.addIfNotNull(currentToken.toString().nullize(true))
    return tokens
  }

  override fun dispose() {}

  companion object {
    private val EMPTY_MAPPINGS = Mappings()
    private const val MAILMAP_FILE_NAME = ".mailmap"
  }
}