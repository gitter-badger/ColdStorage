package com.arcane.coldstoragecache.helper

import android.animation.ValueAnimator
import android.app.Activity
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.Fragment

class BindHelper {

    companion object {

        fun bindViewToResource(anyObject: Any, resourceId: Int): ImageView {
            return when (anyObject) {
                is Activity -> {
                    bindView(anyObject, resourceId)
                }
                is View -> {
                    bindView(anyObject, resourceId)
                }
                is Fragment -> {
                    bindView(anyObject, resourceId)
                }
                else -> {
                    throw Exception("Only views , activities and fragments are supported for the annotation")
                }
            }
        }

        private fun bindView(fragment: Fragment, resourceId: Int): ImageView {
            if (fragment.view != null) {
                return fragment.view!!.findViewById(resourceId)
            } else {
                throw Exception("Unable to get the root view of the fragment ${fragment.javaClass.simpleName}")
            }
        }

        private fun bindView(activity: Activity, resourceId: Int): ImageView {
            return activity.window.decorView.findViewById(resourceId)
        }


        private fun bindView(view: View, resourceId: Int): ImageView {
            return view.findViewById(resourceId)
        }

        fun animateImageView(imageView: ImageView): ValueAnimator {
            val animator = ValueAnimator.ofFloat(0f, 360f)
            animator.duration = 2000
            animator.addUpdateListener {
                val animatedValue = it.animatedValue as Float
                imageView.rotation = animatedValue
            }
            animator.repeatCount = ValueAnimator.INFINITE
            return animator
        }
    }
}