package ceui.pixiv.ui.user

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPagedListBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.User
import ceui.loxia.UserResponse
import ceui.pixiv.paging.PagingUserAPIRepository
import ceui.pixiv.paging.pagingViewModel
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.TitledViewPagerFragment
import ceui.pixiv.ui.common.pixivValueViewModel
import ceui.pixiv.ui.common.repo.RemoteRepository
import ceui.pixiv.ui.common.setUpPagedList
import ceui.pixiv.ui.common.viewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import com.bumptech.glide.request.target.Target
import jp.wasabeef.glide.transformations.BlurTransformation

class UserFollowingFragment : PixivFragment(R.layout.fragment_paged_list) {

    private val binding by viewBinding(FragmentPagedListBinding::bind)
    private val safeArgs by navArgs<UserFollowingFragmentArgs>()
    private val viewModel by pagingViewModel({ safeArgs }) { args ->
        PagingUserAPIRepository {
            Client.appApi.getFollowingUsers(args.userId, args.restrictType)
        }
    }
    private val contentViewModel by pixivValueViewModel({ safeArgs }) { args ->
        RemoteRepository {
            val rest = if (args.restrictType == Params.TYPE_PRIVATE) {
                "hide"
            } else {
                "show"
            }
            Client.webApi.getRelatedUsers(SessionManager.loggedInUid, "following", rest)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPagedList(binding, viewModel, ListMode.VERTICAL)
        if (safeArgs.userId == SessionManager.loggedInUid) {
            if (safeArgs.restrictType == Params.TYPE_PUBLIC) {
                ObjectPool.get<UserResponse>(safeArgs.userId).observe(viewLifecycleOwner) { user ->
                    (parentFragment as? TitledViewPagerFragment)?.let {
                        it.getTitleLiveData(0).value =
                            "${getString(R.string.string_391)} (${user.profile?.total_follow_users ?: 0})"
                    }
                }
            } else if (safeArgs.restrictType == Params.TYPE_PRIVATE) {
                contentViewModel.result.observe(viewLifecycleOwner) { loadResult ->
                    (parentFragment as? TitledViewPagerFragment)?.let {
                        val result = loadResult?.data ?: return@observe
                        it.getTitleLiveData(1).value =
                            "${getString(R.string.string_392)} (${result.body?.total ?: 0})"
                    }
                }
            }
        }
    }
}

const val NO_PROFILE_IMG = "https://s.pximg.net/common/images/no_profile.png"

@BindingAdapter("userIcon")
fun ImageView.binding_loadUserIcon(user: User?) {
    val url = user?.profile_image_urls?.findMaxSizeUrl() ?: return

    val self = this

    val existing = self.getTag(R.id.user_head_icon_tag) as? String
    if (existing == url) {
        return
    }

    scaleType = ImageView.ScaleType.CENTER_CROP
    if (url == NO_PROFILE_IMG) {
        Glide.with(this)
            .load(R.drawable.icon_user_mask)
            .into(this)
    } else {
        Glide.with(this)
            .load(GlideUrlChild(url))
            .placeholder(R.drawable.icon_user_mask)
            .addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    self.setTag(R.id.user_head_icon_tag, null)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    self.setTag(R.id.user_head_icon_tag, url)
                    return false
                }
            })
            .into(this)
    }
}

@BindingAdapter("loadSquareMedia")
fun ImageView.binding_loadSquareMedia(illust: Illust?) {
    val url = illust?.image_urls?.square_medium ?: return
    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.image_place_holder_r2)
        .into(this)
}

@BindingAdapter("loadMedia")
fun ImageView.binding_loadMedia(displayUrl: String?) {
    val url = displayUrl ?: return
    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.image_place_holder)
        .into(this)
}

@BindingAdapter("loadBlurredMedia")
fun ImageView.binding_loadBlurredMedia(displayUrl: String?) {
    val url = displayUrl ?: return
    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(GlideUrlChild(url))
        .placeholder(R.drawable.image_place_holder)
        .apply(bitmapTransform(BlurTransformation(25, 3)))
        .transition(withCrossFade())
        .into(this)
}

fun TextView.setTextOrGone(content: String?) {
    if (content?.isNotEmpty() == true) {
        isVisible = true
        text = content
    } else {
        isVisible = false
    }
}