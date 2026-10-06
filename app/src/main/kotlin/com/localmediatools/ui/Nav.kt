package com.localmediatools.ui

import android.content.Intent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.localmediatools.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A full-screen page. Screens are plain View hierarchies managed by [Navigator]. */
abstract class Screen(val activity: MainActivity) {
    val ctx get() = activity
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var _view: View? = null
    val view: View get() = _view ?: createView().also { _view = it }

    protected abstract fun createView(): View
    /** Called every time the screen becomes the visible one. */
    open fun onShow() {}
    open fun onHide() {}
    open fun onDestroy() { scope.cancel() }
    /** Return true if the screen handled Back itself. */
    open fun onBack(): Boolean = false
    open fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {}

    fun push(s: Screen) = activity.navigator.push(s)
    fun pop() = activity.navigator.pop()
}

/** Screen stack with slide/fade transitions. */
class Navigator(private val container: FrameLayout) {
    private val stack = ArrayList<Screen>()
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    val top: Screen? get() = stack.lastOrNull()
    val depth get() = stack.size

    fun root(s: Screen) {
        stack.forEach { it.onHide(); it.onDestroy() }
        stack.clear()
        container.removeAllViews()
        stack.add(s)
        container.addView(s.view)
        s.onShow()
    }

    fun push(s: Screen) {
        val prev = stack.lastOrNull()
        stack.add(s)
        val v = s.view
        if (v.parent != null) (v.parent as FrameLayout).removeView(v)
        container.addView(v)
        val w = container.width.takeIf { it > 0 } ?: container.resources.displayMetrics.widthPixels
        v.translationX = w * 0.25f
        v.alpha = 0f
        v.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(ease).start()
        prev?.let { p ->
            p.onHide()
            p.view.animate().translationX(-w * 0.08f).alpha(0f).setDuration(220).setInterpolator(ease).withEndAction {
                if (stack.lastOrNull() !== p) p.view.visibility = View.GONE
            }.start()
        }
        s.onShow()
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        val s = stack.removeAt(stack.size - 1)
        val prev = stack.last()
        val w = container.width.toFloat()
        s.onHide()
        s.view.animate().translationX(w * 0.25f).alpha(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).withEndAction {
            container.removeView(s.view)
            s.onDestroy()
        }.start()
        prev.view.visibility = View.VISIBLE
        prev.view.translationX = -w * 0.08f
        prev.view.alpha = 0f
        prev.view.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(ease).start()
        prev.onShow()
        return true
    }

    fun back(): Boolean {
        val t = top ?: return false
        if (t.onBack()) return true
        return pop()
    }

    fun dispatchResult(requestCode: Int, resultCode: Int, data: Intent?) {
        stack.forEach { it.onActivityResult(requestCode, resultCode, data) }
    }

    fun all(): List<Screen> = stack.toList()
}
