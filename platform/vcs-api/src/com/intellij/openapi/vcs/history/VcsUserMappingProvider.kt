// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.history

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsUser
import org.jetbrains.annotations.ApiStatus

/**
 * This interface is intended for VCSs that support the ability to override revision committer/author name and/or email.
 *
 * For instance, this interface can support .mailmap in Git and Mercurial
 *
 * @see VCS_USER_MAPPINGS_UPDATED
 *
 * @author Freya Arbjerg
 */
@ApiStatus.Experimental
interface VcsUserMappingProvider {

  /**
   * @return whether this provider is ready to provide mappings for the given VCS root
   */
  fun isReady(vcsRoot: VirtualFile): Boolean

  /**
   * @return the overriding name and email, or null if no match is found
   */
  fun map(vcsRoot: VirtualFile, user: VcsUser): VcsUser? {
    return getMappings(vcsRoot)?.get(user)
  }

  /**
   * @return the mappings for the given VCS root, if any are available
   */
  fun getMappings(vcsRoot: VirtualFile): VcsUserMappings?

}
