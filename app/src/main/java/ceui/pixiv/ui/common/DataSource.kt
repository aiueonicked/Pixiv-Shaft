package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.loxia.Client
import ceui.loxia.KListShow
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import com.google.gson.Gson
import kotlinx.coroutines.delay

open class DataSource<Item, T: KListShow<Item>>(
    private val dataFetcher: suspend () -> T,
    itemMapper: (Item) -> List<ListItemHolder>,
    private val filter: (Item) -> Boolean = { _ -> true }
) {
    private var _variableItemMapper: ((Item) -> List<ListItemHolder>)? = null

    init {
        _variableItemMapper = itemMapper
    }

    private val currentProtoItems = mutableListOf<Item>()

    private val _itemHolders = MutableLiveData<List<ListItemHolder>>()
    val itemHolders: LiveData<List<ListItemHolder>> = _itemHolders

    private var _nextPageUrl: String? = null
    private val gson = Gson()

    private var responseClass: Class<T>? = null

    private val _refreshState = MutableLiveData<RefreshState>()
    val refreshState: LiveData<RefreshState> = _refreshState

    open suspend fun refreshData(hint: RefreshHint) {
        _refreshState.value = RefreshState.LOADING(refreshHint = hint)
        try {
            if (hint == RefreshHint.ErrorRetry) {
                delay(300L)
            }
            val response = dataFetcher()
            currentProtoItems.clear()
            responseClass = response::class.java as Class<T>
            _nextPageUrl = response.nextPageUrl
            currentProtoItems.addAll(response.displayList)
            mapProtoItemsToHolders()
            _refreshState.value = RefreshState.LOADED(
                hasContent = _itemHolders.value?.isNotEmpty() == true,
                hasNext = _nextPageUrl?.isNotEmpty() == true
            )
        } catch (ex: Exception) {
            _refreshState.value = RefreshState.ERROR(ex)
            ex.printStackTrace()
        }
    }

    open suspend fun loadMoreData() {
        val nextPageUrl = _nextPageUrl ?: return
        _refreshState.value = RefreshState.LOADING(refreshHint = RefreshHint.LoadMore)
        try {
            val responseBody = Client.appApi.generalGet(nextPageUrl)
            val responseJson = responseBody.string()
            val response = gson.fromJson(responseJson, responseClass)
            _nextPageUrl = response.nextPageUrl

            if (response.displayList.isNotEmpty()) {
                currentProtoItems.addAll(response.displayList)
                mapProtoItemsToHolders()
            }
            _refreshState.value = RefreshState.LOADED(
                hasContent = _itemHolders.value?.isNotEmpty() == true,
                hasNext = _nextPageUrl?.isNotEmpty() == true
            )
        } catch (ex: Exception) {
            _refreshState.value = RefreshState.ERROR(ex)
            ex.printStackTrace()
        }
    }

    private fun mapProtoItemsToHolders() {
        val mapper = _variableItemMapper ?: return
        val holders = currentProtoItems
            .filter { item ->
                filter(item)
            }
            .flatMap(mapper)
        _itemHolders.value = holders
    }

    fun updateMapper(mapper: (Item) -> List<ListItemHolder>) {
        _itemHolders.value = listOf()
        this._variableItemMapper = mapper
        mapProtoItemsToHolders()
    }

    protected fun pickItemHolders(): MutableLiveData<List<ListItemHolder>> {
        return _itemHolders
    }

    open fun initialLoad(): Boolean {
        return true
    }
}
