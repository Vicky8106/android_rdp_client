package com.rdp.client.ui.session

import android.content.Context
import android.util.AttributeSet

/**
 * Re-export FrameView in package com.rdp.client.ui.session for layout XML resolution
 * and backwards-compatible imports.
 */
class FrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : com.rdp.client.ui.session.viewport.FrameView(context, attrs, defStyleAttr)
