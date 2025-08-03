package ceui.pixiv.ui.novel

import ceui.lisa.R
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.WebNovelParser
import ceui.lisa.repo.TranslateTextRepo
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.SpaceHolder
import ceui.loxia.WebNovel
import ceui.loxia.novel.NovelTextHolder
import ceui.pixiv.ui.chats.RedSectionHeaderHolder
import ceui.pixiv.ui.common.HoldersContainer
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.RefreshOwner
import ceui.pixiv.ui.detail.UserInfoHolder
import kotlinx.coroutines.launch
import timber.log.Timber

class NovelTextViewModel(
    private val novelId: Long,
) : HoldersViewModel() {

    private val _webNovel = MutableLiveData<WebNovel>()
    val webNovel: LiveData<WebNovel> = _webNovel

    private val translateRepo = TranslateTextRepo()

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        val context = Shaft.getContext()
        val html = Client.appApi.getNovelText(novelId).string()
        val wNovel = WebNovelParser.parsePixivObject(html)?.novel

        val result = mutableListOf<ListItemHolder>()
        result.add(SpaceHolder())
        result.add(NovelHeaderHolder(novelId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.string_432)))
        result.add(UserInfoHolder(ObjectPool.get<Novel>(novelId).value?.user?.id ?: 0L))
        result.add(RedSectionHeaderHolder(context.getString(R.string.description)))
        result.add(NovelCaptionHolder(novelId))
        result.add(RedSectionHeaderHolder(context.getString(R.string.string_433)))
        result.add(SpaceHolder())

        wNovel?.let {
            (wNovel.text?.split("\n") ?: listOf()).forEach { oneLineText ->
                result.addAll(
                    WebNovelParser.buildNovelHolders(wNovel, oneLineText)
                )
            }
            _webNovel.value = it
        }
        result.add(SpaceHolder())
        result.add(NovelTextHolder("<===== End =====>", Common.getNovelTextColor()))
        result.add(SpaceHolder())

        _itemHolders.value = result
        _refreshState.value = RefreshState.LOADED(
            hasContent = true, hasNext = false
        )
    }

    fun translateNovel() {
        val originalText = webNovel.value?.text
        if (originalText.isNullOrBlank()) {
            return
        }

        viewModelScope.launch {
            val currentHolders = _itemHolders.value.orEmpty()
            val context = Shaft.getContext()

            val headerEndIndex = currentHolders.indexOfFirst {
                it is RedSectionHeaderHolder && it.title == context.getString(R.string.string_433)
            }
            if (headerEndIndex == -1) return@launch
            val headers = currentHolders.take(headerEndIndex + 2)

            val translatingList = headers.toMutableList().apply {
                add(NovelTextHolder(context.getString(R.string.translating), Common.getNovelTextColor()))
            }
            _itemHolders.value = translatingList

            val fullTranslatedText = StringBuilder()

            translateRepo.translate(
                text = originalText,
                onResult = { partialResult ->
                    fullTranslatedText.append(partialResult)
                    val newTextHolders = fullTranslatedText.toString().split('\n').map { line ->
                        NovelTextHolder(line, Common.getNovelTextColor())
                    }
                    _itemHolders.postValue(headers + newTextHolders)
                },
                onError = { errorMsg ->
                    _itemHolders.postValue(headers.toMutableList().apply {
                        add(NovelTextHolder("${context.getString(R.string.translation_error)} $errorMsg", Common.getNovelTextColor()))
                    })
                },
                onComplete = {
                    val finalList = (_itemHolders.value ?: headers).toMutableList()
                    finalList.add(SpaceHolder())
                    finalList.add(NovelTextHolder("<===== End =====>", Common.getNovelTextColor()))
                    finalList.add(SpaceHolder())
                    _itemHolders.postValue(finalList)
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        translateRepo.cancel()
    }
}
