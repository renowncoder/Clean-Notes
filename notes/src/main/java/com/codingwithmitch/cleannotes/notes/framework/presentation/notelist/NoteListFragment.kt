package com.codingwithmitch.cleannotes.notes.framework.presentation.notelist

import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codingwithmitch.cleannotes.core.business.state.*
import com.codingwithmitch.cleannotes.core.framework.DialogInputCaptureCallback
import com.codingwithmitch.cleannotes.core.framework.TopSpacingItemDecoration
import com.codingwithmitch.cleannotes.core.framework.hideKeyboard
import com.codingwithmitch.cleannotes.core.util.TodoCallback
import com.codingwithmitch.cleannotes.core.util.printLogD
import com.codingwithmitch.cleannotes.notes.business.domain.model.Note
import com.codingwithmitch.cleannotes.notes.business.interactors.common.DeleteNote.Companion.DELETE_NOTE_PENDING
import com.codingwithmitch.cleannotes.notes.business.interactors.common.DeleteNote.Companion.DELETE_NOTE_SUCCESS
import com.codingwithmitch.cleannotes.notes.business.interactors.notelistfragment.DeleteMultipleNotes.Companion.DELETE_NOTES_ARE_YOU_SURE
import com.codingwithmitch.cleannotes.notes.business.interactors.notelistfragment.DeleteMultipleNotes.Companion.DELETE_NOTES_SUCCESS
import com.codingwithmitch.cleannotes.notes.framework.presentation.BaseNoteFragment
import com.codingwithmitch.cleannotes.notes.framework.presentation.notedetail.NOTE_DETAIL_SELECTED_NOTE_BUNDLE_KEY
import com.codingwithmitch.cleannotes.notes.framework.presentation.notelist.state.NoteListStateEvent.*
import com.codingwithmitch.cleannotes.notes.framework.presentation.notelist.state.NoteListToolbarState.*
import com.codingwithmitch.cleannotes.notes.framework.presentation.notelist.state.NoteListViewState
import com.codingwithmitch.notes.R
import kotlinx.android.synthetic.main.fragment_note_list.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import javax.inject.Inject


const val NOTE_LIST_STATE_BUNDLE_KEY = "com.codingwithmitch.cleannotes.notes.framework.presentation.notelist.state"

@FlowPreview
@ExperimentalCoroutinesApi
class NoteListFragment : BaseNoteFragment(R.layout.fragment_note_list),
    NoteListAdapter.Interaction,
    ItemTouchHelperAdapter
{

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    val viewModel: NoteListViewModel by viewModels {
        viewModelFactory
    }

    private var listAdapter: NoteListAdapter? = null
    private var itemTouchHelper: ItemTouchHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setupChannel()
        arguments?.let { args ->
            args.getParcelable<Note>(NOTE_PENDING_DELETE_BUNDLE_KEY)?.let { note ->
                viewModel.setNotePendingDelete(note)
                showUndoSnackbar_deleteNote()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFAB()
        subscribeObservers()

        restoreInstanceState(savedInstanceState)
    }


    override fun onResume() {
        super.onResume()
        viewModel.setStateEvent(GetNumNotesInCacheEvent())
        viewModel.restoreFromCache()
    }

    override fun onPause() {
        super.onPause()
        saveLayoutManagerState()
    }

    private fun restoreInstanceState(savedInstanceState: Bundle?){
        savedInstanceState?.let { inState ->
            (inState[NOTE_LIST_STATE_BUNDLE_KEY] as NoteListViewState?)?.let { viewState ->
                viewModel.setViewState(viewState)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val viewState = viewModel.viewState.value

        //clear the list. Don't want to save a large list to bundle.
        viewState?.noteList =  ArrayList()

        outState.putParcelable(
            NOTE_LIST_STATE_BUNDLE_KEY,
            viewState
        )
        super.onSaveInstanceState(outState)
    }

    override fun restoreListPosition() {
        viewModel.viewState.value?.layoutManagerState?.let { lmState ->
            recycler_view?.layoutManager?.onRestoreInstanceState(lmState)
        }
    }

    private fun saveLayoutManagerState(){
        recycler_view.layoutManager?.onSaveInstanceState()?.let { lmState ->
            viewModel.setLayoutManagerState(lmState)
        }
    }

    private fun setupRecyclerView(){
        recycler_view.apply {
            layoutManager = LinearLayoutManager(activity)
            val topSpacingDecorator = TopSpacingItemDecoration(20)
            addItemDecoration(topSpacingDecorator)
            itemTouchHelper = ItemTouchHelper(
                NoteItemTouchHelperCallback(
                    this@NoteListFragment,
                    viewModel.noteListInteractionManager
                )
            )
            listAdapter = NoteListAdapter(
                this@NoteListFragment,
                viewLifecycleOwner,
                viewModel.noteListInteractionManager.selectedNotes
            )
            itemTouchHelper?.attachToRecyclerView(this)
            addOnScrollListener(object: RecyclerView.OnScrollListener(){
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastPosition = layoutManager.findLastVisibleItemPosition()
                    if (lastPosition == listAdapter?.itemCount?.minus(1)) {
                        viewModel.nextPage()
                    }
                }
            })
            adapter = listAdapter
        }
    }

    private fun enableMultiSelectToolbarState(){
        view?.let { v ->
            val view = View.inflate(
                v.context,
                R.layout.layout_multiselection_toolbar,
                null
            )
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            toolbar_content_container.addView(view)
            setupMultiSelectionToolbar(view)
        }
    }

    private fun setupMultiSelectionToolbar(parentView: View){
        parentView
                .findViewById<ImageView>(R.id.action_exit_multiselect_state)
            .setOnClickListener {
                viewModel.setToolbarState(SearchViewState())
            }

        parentView
            .findViewById<ImageView>(R.id.action_delete_notes)
            .setOnClickListener {
                deleteNotes()
            }
    }

    private fun deleteNotes(){
        viewModel.setStateEvent(
            CreateStateMessageEvent(
                stateMessage = StateMessage(
                    response = Response(
                        message = DELETE_NOTES_ARE_YOU_SURE,
                        uiComponentType = UIComponentType.AreYouSureDialog(
                            object : AreYouSureCallback {
                                override fun proceed() {
                                    viewModel.deleteNotes()
                                }

                                override fun cancel() {
                                    // do nothing
                                }
                            }
                        ),
                        messageType = MessageType.Info()
                    )
                )
            )
        )
    }

    private fun enableSearchViewToolbarState(){
        view?.let { v ->
            val view = View.inflate(
                v.context,
                R.layout.layout_searchview_toolbar,
                null
            )
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            toolbar_content_container.addView(view)
            setupSearchView()
        }
    }

    private fun disableMultiSelectToolbarState(){
        view?.let { v ->
            val view = toolbar_content_container
                .findViewById<Toolbar>(R.id.multiselect_toolbar)
            toolbar_content_container.removeView(view)
            viewModel.clearSelectedNotes()
        }
    }

    private fun disableSearchViewToolbarState(){
        view?.let { v ->
            val view = toolbar_content_container
                .findViewById<Toolbar>(R.id.searchview_toolbar)
            toolbar_content_container.removeView(view)
        }
    }

    override fun isMultiSelectionModeEnabled()
            = viewModel.isMultiSelectionStateActive()

    override fun activateMultiSelectionMode()
            = viewModel.setToolbarState(MultiSelectionState())

    private fun subscribeObservers(){

        viewModel.toolbarState.observe(viewLifecycleOwner, Observer{ toolbarState ->

            when(toolbarState){

                is MultiSelectionState -> {
                    enableMultiSelectToolbarState()
                    disableSearchViewToolbarState()
                }

                is SearchViewState -> {
                    enableSearchViewToolbarState()
                    disableMultiSelectToolbarState()
                }
            }
        })

        viewModel.viewState.observe(viewLifecycleOwner, Observer{ viewState ->

            if(viewState != null){
                viewState.noteList?.let { noteList ->
                    if(viewModel.isPaginationExhausted()
                        && !viewModel.isQueryExhausted()){
                        viewModel.setQueryExhausted(true)
                    }
                    listAdapter?.submitList(noteList)
                    listAdapter?.notifyDataSetChanged()
                }

                // a note been inserted or selected
                viewState.newNote?.let { newNote ->
                    navigateToDetailFragment(newNote)
                }

            }
        })

        viewModel.shouldDisplayProgressBar.observe(viewLifecycleOwner, Observer {
            printActiveJobs()
            uiController.displayProgressBar(it)
        })

        viewModel.stateMessage.observe(viewLifecycleOwner, Observer { stateMessage ->
            stateMessage?.let { message ->
                if(message.response.message?.equals(DELETE_NOTE_SUCCESS) == true){
                    showUndoSnackbar_deleteNote()
                }
                else{
                    uiController.onResponseReceived(
                        response = message.response,
                        stateMessageCallback = object: StateMessageCallback {
                            override fun removeMessageFromStack() {
                                viewModel.clearStateMessage()
                            }
                        }
                    )
                }
            }
        })
    }

    private fun showUndoSnackbar_deleteNote(){
        uiController.onResponseReceived(
            response = Response(
                message = DELETE_NOTE_PENDING,
                uiComponentType = UIComponentType.SnackBar(
                    undoCallback = object : SnackbarUndoCallback {
                        override fun undo() {
                            viewModel.undoDelete()
                        }
                    },
                    onDismissCallback = object: TodoCallback{
                        override fun execute() {
                            // if the note is not restored, clear pending note
                            viewModel.setNotePendingDelete(null)
                        }
                    }
                ),
                messageType = MessageType.Info()
            ),
            stateMessageCallback = object: StateMessageCallback{
                override fun removeMessageFromStack() {
                    viewModel.clearStateMessage()
                }
            }
        )
    }

    // for debugging
    private fun printActiveJobs(){
        for((index, job) in viewModel.getActiveJobs().withIndex()){
            printLogD("NoteList",
                "${index}: ${job}")
        }
    }

    private fun navigateToDetailFragment(selectedNote: Note){
        val bundle = bundleOf(NOTE_DETAIL_SELECTED_NOTE_BUNDLE_KEY to selectedNote)
        findNavController().navigate(
            R.id.action_note_list_fragment_to_noteDetailFragment,
            bundle
        )
        viewModel.setNote(null)
    }

    private fun setupUI(){
        view?.hideKeyboard()
        uiController.checkBottomNav(getString(com.codingwithmitch.cleannotes.R.string.module_notes_name))
        uiController.displayBottomNav(true)
    }

    override fun inject() {
        getNoteComponent()?.inject(this)
    }

    override fun onItemSelected(position: Int, item: Note) {
        if(isMultiSelectionModeEnabled()){
            viewModel.addOrRemoveNoteFromSelectedList(item)
        }
        else{
            viewModel.setNote(item)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listAdapter = null // can leak memory
        itemTouchHelper = null // can leak memory
    }

    override fun isNoteSelected(note: Note): Boolean {
        return viewModel.isNoteSelected(note)
    }

    override fun onItemSwiped(position: Int) {
        if(!viewModel.isDeletePending()){
            listAdapter?.getNote(position)?.let { note ->
                viewModel.beginPendingDelete(note)
            }
        }
        else{
            listAdapter?.notifyDataSetChanged()
        }
    }

    private fun setupSearchView(){

        val searchViewToolbar: Toolbar? = toolbar_content_container
            .findViewById<Toolbar>(R.id.searchview_toolbar)

        searchViewToolbar?.let { toolbar ->

            val searchView = toolbar.findViewById<SearchView>(R.id.search_view)

            val searchPlate: SearchView.SearchAutoComplete?
                    = searchView.findViewById(androidx.appcompat.R.id.search_src_text)

            searchPlate?.setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_UNSPECIFIED
                    || actionId == EditorInfo.IME_ACTION_SEARCH ) {
                    val searchQuery = v.text.toString()
                    viewModel.setQuery(searchQuery)
                    startNewSearch()
                }
                true
            }
        }
    }



    private fun setupFAB(){
        add_new_note_fab.setOnClickListener {
            uiController.displayInputCaptureDialog(
                getString(com.codingwithmitch.cleannotes.R.string.text_enter_a_title),
                object: DialogInputCaptureCallback{
                    override fun onTextCaptured(text: String) {
                        val newNote = viewModel.createNewNote(title = text)
                        viewModel.setStateEvent(
                            InsertNewNoteEvent(
                                title = newNote.title,
                                body = ""
                            )
                        )
                    }
                }
            )
        }
    }

    private fun startNewSearch() = viewModel.loadFirstPage()

    private fun setupSwipeRefresh(){
        swipe_refresh.setOnRefreshListener {
            startNewSearch()
            swipe_refresh.isRefreshing = false
        }
    }

}










































