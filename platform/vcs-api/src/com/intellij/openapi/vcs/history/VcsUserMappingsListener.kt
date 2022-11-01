// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.history

import com.intellij.openapi.vcs.VcsRoot
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
val VCS_USER_MAPPINGS_UPDATED = Topic(VcsUserMappingsListener::class.java, Topic.BroadcastDirection.NONE)

/**
 * @author Freya Arbjerg
 */
@ApiStatus.Experimental
interface VcsUserMappingsListener {
  fun onMappingsUpdated(vcsRoot: VcsRoot, mappings: VcsUserMappings)
}
