// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.history

import com.intellij.vcs.log.VcsUser

/**
 * @author Freya Arbjerg
 */
interface VcsUserMappings {
  /**
   * @return a mapped user identity according to these mappings, or null if no matches were found
   */
  fun get(user: VcsUser): VcsUser?
}
