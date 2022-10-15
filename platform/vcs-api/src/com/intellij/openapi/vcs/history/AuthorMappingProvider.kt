// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.history

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsUser
import org.jetbrains.annotations.ApiStatus

/**
 * This interface is intended for VCSs that support the ability to override revision author name and/or email.
 *
 * For instance, this interface can support .mailmap in Git and Mercurial
 *
 * @author Freya Arbjerg
 */
@ApiStatus.Experimental
interface AuthorMappingProvider {

  /**
   * @return whether this provider is ready to provide mappings
   */
  fun isReady(vcsRoot: VirtualFile): Boolean

  /**
   * @return the overriding name and email, or null if no match is found
   */
  operator fun get(vcsRoot: VirtualFile, user: VcsUser): VcsUser?

}