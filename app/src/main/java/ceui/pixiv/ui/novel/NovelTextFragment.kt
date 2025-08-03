package ceui.pixiv.ui.novel

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.combineLatest
import ceui.loxia.pushFragment
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.ui.comments.CommentsFragmentArgs
import ceui.pixiv.ui.common.FitsSystemWindowFragment
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.constructVM
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.shareNovel
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.task.DownloadNovelTask
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.MenuItem
import ceui.pixiv.widgets.showActionMenu


class NovelTextFragment : PixivFragment(R.layout.fragment_pixiv_list), FitsSystemWindowFragment,
    NovelSeriesActionReceiver {

    private val safeArgs by navArgs<NovelTextFragmentArgs>()
    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val textModel by constructVM({ safeArgs.novelId }) { novelId ->
        NovelTextViewModel(novelId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, textModel, ListMode.VERTICAL)

        val liveNovel = ObjectPool.get<Novel>(safeArgs.novelId)
        combineLatest(
            liveNovel,
            textModel.webNovel
        ).observe(viewLifecycleOwner) { (novel, webNovel) ->

            if (novel != null) {
                runOnceWithinFragmentLifecycle("visit-novel-${safeArgs.novelId}") {
                    requireEntityWrapper().visitNovel(requireContext(), novel)
                }
            }

            binding.toolbarLayout.naviMore.setOnClick {
                if (novel == null || webNovel == null) {
                    return@setOnClick
                }

                val authorId = novel.user?.id ?: 0L
                showActionMenu {
                    add(
                        MenuItem(getString(R.string.view_comments)) {
                            pushFragment(
                                R.id.navigation_comments_illust,
                                CommentsFragmentArgs(
                                    safeArgs.novelId,
                                    authorId,
                                    ObjectType.NOVEL
                                ).toBundle()
                            )
                        }
                    )
                    add(
                        MenuItem(getString(R.string.string_110)) {
                            shareNovel(novel)
                        }
                    )
                    add(
                        MenuItem(getString(R.string.string_5)) {
                            DownloadNovelTask(
                                requireActivity().lifecycleScope,
                                novel,
                                webNovel
                            ).start {

                            }
                        }
                    )
                    add(
                        MenuItem(getString(R.string.translate)) {
                            textModel.translateNovel()
                        }
                    )
                }
            }
        }
    }
}
