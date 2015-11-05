package com.cgogolin.penandpdf;

import android.util.SparseArray;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.preference.PreferenceManager;
import android.widget.Toast;
import android.graphics.RectF;

import android.util.Log;

import java.lang.Math;

abstract public class MuPDFReaderView extends ReaderView {
    enum Mode {Viewing, Selecting, Drawing, Erasing, AddingTextAnnot}
    private final Context mContext;
    private boolean mLinksEnabled = true;
    private Mode mMode = Mode.Viewing;
    private boolean tapDisabled = false;
    private int tapPageMargin;
//    private static final int BACKGROUND_COLOR = 0xF0F0F0F0;
    
    private SparseArray<SearchResult> SearchResults = new SparseArray<SearchResult>();    
        //To be overwritten in PenAndPDFActivity:
    abstract protected void onMoveToChild(int pageNumber);
    abstract protected void onTapMainDocArea();
    abstract protected void onTapTopLeftMargin();
    abstract protected void onBottomRightMargin();
    abstract protected void onDocMotion();
    abstract protected void onHit(Hit item);
    abstract protected void onNumberOfStrokesChanged(int numberOfStrokes);
    abstract protected void addTextAnnotFromUserInput(Annotation annot);

        //Gesture detector for long press
    private final Handler longPressHandler = new Handler();
    private MotionEvent longPressStartEvent;
    Runnable longPressed;
    
    public void setLinksEnabled(boolean b) {
        mLinksEnabled = b;
        resetupChildren();
    }

    public boolean linksEnabled() {
        return mLinksEnabled;
    }

    public void setMode(Mode m) {
        mMode = m;
    }

    public Mode getMode() {
        return mMode;
    }

    public MuPDFReaderView(Activity act) {
        super(act);
        mContext = act;
//        setBackgroundColor(BACKGROUND_COLOR);
            // Get the screen size etc to customise tap margins.
            // We calculate the size of 1 inch of the screen for tapping.
            // On some devices the dpi values returned are wrong, so we
            // sanity check it: we first restrict it so that we are never
            // less than 100 pixels (the smallest Android device screen
            // dimension I've seen is 480 pixels or so). Then we check
            // to ensure we are never more than 1/5 of the screen width.
        DisplayMetrics dm = new DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getMetrics(dm);
        tapPageMargin = (int)dm.xdpi;
        if (tapPageMargin < 100)
            tapPageMargin = 100;
        if (tapPageMargin > dm.widthPixels/5)
            tapPageMargin = dm.widthPixels/5;
        if (tapPageMargin > dm.heightPixels/5)
            tapPageMargin = dm.heightPixels/5;
    }

    public boolean onSingleTapUp(MotionEvent e) {
        MuPDFView pageView = (MuPDFView)getSelectedView();
        if (pageView == null ) return super.onSingleTapUp(e);

            //Stop the long tap handler
        longPressHandler.removeCallbacks(longPressed);
        longPressStartEvent = null;
        
        if (mMode == Mode.Viewing && !tapDisabled) {
            Hit item = pageView.passClickEvent(e);
            onHit(item);
                
            LinkInfo link = null;
            if (mLinksEnabled && (item == Hit.LinkInternal || item == Hit.LinkExternal || item == Hit.LinkRemote) && (link = pageView.hitLink(e.getX(), e.getY())) != null)
            {
                link.acceptVisitor(new LinkInfoVisitor() {
                        @Override
                        public void visitInternal(LinkInfoInternal li) {
                                // Clicked on an internal (GoTo) link
                            setDisplayedViewIndex(li.pageNumber);
                            if(li.target != null)
                            {
                                    //Scroll the left top to the right position
                                if((li.targetFlags & LinkInfoInternal.fz_link_flag_l_valid) == LinkInfoInternal.fz_link_flag_l_valid)  
                                    setDocRelXScroll(li.target.left);
                                if((li.targetFlags & LinkInfoInternal.fz_link_flag_t_valid) == LinkInfoInternal.fz_link_flag_t_valid)
                                    setDocRelYScroll(li.target.top);
                                    //If the link target is of /XYZ type r might be a zoom value
                                if( (li.targetFlags & LinkInfoInternal.fz_link_flag_r_is_zoom) == LinkInfoInternal.fz_link_flag_r_is_zoom && (li.targetFlags & LinkInfoInternal.fz_link_flag_r_valid) == LinkInfoInternal.fz_link_flag_r_valid )
                                {
                                    Toast.makeText(getContext(), "zoom="+li.target.right, Toast.LENGTH_SHORT).show();
                                    if(li.target.right > 0 && li.target.right <= 1.0f)
                                        setScale(li.target.right);
                                }
                                    
                                if( (li.targetFlags & LinkInfoInternal.fz_link_flag_fit_h) == LinkInfoInternal.fz_link_flag_fit_h && (li.targetFlags & LinkInfoInternal.fz_link_flag_fit_v) == LinkInfoInternal.fz_link_flag_fit_v )
                                {
                                    setScale(1.0f);
                                }
                                else if( (li.targetFlags & LinkInfoInternal.fz_link_flag_fit_h) == LinkInfoInternal.fz_link_flag_fit_h )
                                {
                                        //Fit width
                                }
                                else if( (li.targetFlags & LinkInfoInternal.fz_link_flag_fit_v) == LinkInfoInternal.fz_link_flag_fit_v )
                                {
                                        //Fit height
                                }
                                    //NOTE: FitR is not handled!!!
                                    //Toast.makeText(getContext(), "unhandled flags="+li.targetFlags ,Toast.LENGTH_SHORT).show();
                            }
                        }                
                        @Override
                        public void visitExternal(LinkInfoExternal li) {
                                //Clicked on an external link
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri
                                                       .parse(li.url));
                            mContext.startActivity(intent);
                        }
                        @Override
                        public void visitRemote(LinkInfoRemote li) {
                                // Clicked on a remote (GoToR) link
                        }
                    });
            }
            else if(item == Hit.Nothing)
            {
                if (e.getX() > super.getWidth() - tapPageMargin) 
                    onBottomRightMargin();
                else if (e.getX() < tapPageMargin) 
                    onTapTopLeftMargin();
                else if (e.getY() > super.getHeight() - tapPageMargin) 
                    onBottomRightMargin();
                else if (e.getY() < tapPageMargin) 
                    onTapTopLeftMargin();
                else
                    onTapMainDocArea();
            }
        }
        else if(mMode == Mode.AddingTextAnnot && !tapDisabled)
        {
                //Prepare the annotation
            MuPDFPageView cv = (MuPDFPageView)getSelectedView();
            float scale = cv.getScale();
            final float docRelX = (e.getX() - cv.getLeft())/scale;
            final float docRelY = (e.getY() - cv.getTop())/scale;
            final float docRelSize = 0.025f*Math.max(cv.getWidth(), cv.getHeight())/scale;
            Annotation annot = new Annotation(docRelX-0.0f*docRelSize, docRelY+0.0f*docRelSize, docRelX+1.0f*docRelSize, docRelY-1.0f*docRelSize, Annotation.Type.TEXT, null, null); //we set the text to null to indicate that the annotation is new
            
                //Ask the user to provide text
            addTextAnnotFromUserInput(annot);
                //Reset the mode and menu                
            mMode = Mode.Viewing;
            onTapMainDocArea();
        }
        // else if (mMode == Mode.Selecting && !tapDisabled) {
        //     MuPDFView pageView = (MuPDFView)getSelectedView();
        //     if(pageView!=null) pageView.deselectText();            
        // }        
            
        return super.onSingleTapUp(e);
    }


    protected void addTextAnnotion(Annotation annot) {
        MuPDFView pageView = (MuPDFView)getSelectedView();
        ((MuPDFPageView)pageView).addTextAnnotation(annot);
    }
    
    @Override
    public boolean onDown(MotionEvent e) {
        // switch (mMode) {
        //     case Selecting:
        //         MuPDFView pageView = (MuPDFView)getSelectedView();
        //         if(pageView!=null) pageView.deselectText();
        // }
        
            //Make text selectable by long press
        MuPDFPageView cv = (MuPDFPageView)getSelectedView();
        if( (mMode == Mode.Viewing || mMode == Mode.Selecting) && cv != null && !cv.hitsLeftMarker(e.getX(),e.getY()) && !cv.hitsRightMarker(e.getX(),e.getY()) )
        {
            longPressStartEvent = e;
            longPressed = new Runnable() { 
                    public void run() {
                            //Process the long press event
                        if(mMode == Mode.Viewing || mMode == Mode.Selecting)
                        {
                            MuPDFPageView cv = (MuPDFPageView)getSelectedView();
                            if(cv==null) return;
                            setMode(MuPDFReaderView.Mode.Selecting);
                                //Strangely getY() doesn't return the correct coordinate relative to the view on my device so we have to compute them ourselves from the getRaw methods. I hope this works on multiwindow devices...
                            int[] locationOnScreen = new int[2];
                            getLocationOnScreen(locationOnScreen);
                            
                            cv.deselectText();
                            cv.selectText(longPressStartEvent.getX(),longPressStartEvent.getRawY()-locationOnScreen[1],longPressStartEvent.getX()+1,longPressStartEvent.getRawY()+1-locationOnScreen[1]);
                        }
                    }   
                };
            if(!mUseStylus)
                longPressHandler.postDelayed(longPressed, android.view.ViewConfiguration.getLongPressTimeout());
            else
                longPressHandler.postDelayed(longPressed, 2*android.view.ViewConfiguration.getLongPressTimeout()); //Use a longer timeout to interfere less with drawing
        }
        return super.onDown(e);
    }

    private boolean scrollStartedAtLeftMarker = false;
    private boolean scrollStartedAtRightMarker = false;
    
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                            float distanceY) {
        MuPDFPageView pageView = (MuPDFPageView)getSelectedView();

        if( longPressStartEvent != null && (Math.abs(longPressStartEvent.getX() - e1.getX()) > ViewConfiguration.get(mContext).getScaledTouchSlop() || Math.abs(longPressStartEvent.getY() - e1.getY()) > ViewConfiguration.get(mContext).getScaledTouchSlop()) ) 
        {
            longPressHandler.removeCallbacks(longPressed);
            longPressStartEvent = null;
        }
        
        switch (mMode) {
            case Viewing:
                if (!tapDisabled) onDocMotion();
                return super.onScroll(e1, e2, distanceX, distanceY);
            case Selecting:
                if (pageView != null)
                {   
                    if(pageView.hitsLeftMarker(e1.getX(),e1.getY()) || scrollStartedAtLeftMarker) {
                        scrollStartedAtLeftMarker = true;
                        pageView.moveLeftMarker(e2);
                    }
                    else if(pageView.hitsRightMarker(e1.getX(),e1.getY()) || scrollStartedAtRightMarker) {
                        scrollStartedAtRightMarker = true;
                        pageView.moveRightMarker(e2);
                    }
                    else {
                        return super.onScroll(e1, e2, distanceX, distanceY);
                    }
                }
                return false;
            default:
                return true;
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                           float velocityY) {

        longPressHandler.removeCallbacks(longPressed);
        longPressStartEvent = null;
        
        switch (mMode) {
            case Viewing:
            case Selecting:
                return super.onFling(e1, e2, velocityX, velocityY);
            default:
                return true;
        }
    }

    public boolean onScaleBegin(ScaleGestureDetector d) {
            // Disabled showing the buttons until next touch.
            // Not sure why this is needed, but without it
            // pinch zoom can make the buttons appear
        longPressHandler.removeCallbacks(longPressed);
        longPressStartEvent = null;
        
        tapDisabled = true;
        return super.onScaleBegin(d);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final MuPDFPageView pageView = (MuPDFPageView)getSelectedView();
        if (pageView == null) super.onTouchEvent(event);

        switch(event.getAction()) {
            case MotionEvent.ACTION_UP:
                scrollStartedAtLeftMarker = false;
                scrollStartedAtRightMarker = false;
                
                longPressHandler.removeCallbacks(longPressed);
                longPressStartEvent = null;
                break;
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_EXIT:
            case MotionEvent.ACTION_HOVER_MOVE:
                    //If a stylus is hovering interup the long press handler to avoid swithcing to selectio mode
                for(int pointerIndex = 0; pointerIndex < event.getPointerCount(); pointerIndex++) {
                    if (event.getToolType(pointerIndex) == android.view.MotionEvent.TOOL_TYPE_STYLUS) {
                        longPressHandler.removeCallbacks(longPressed);
                        longPressStartEvent = null;
                        break;
                    }
                }
                break;
        }        
        
        //     //Make text selectable by long press
        // if( (mMode == Mode.Viewing || mMode == Mode.Selecting ) && event.getAction() == MotionEvent.ACTION_DOWN)
        // {
        //     longPressStartEvent = event;
        //     longPressed = new Runnable() { 
        //             public void run() {
        //                     //Process the long press event
        //                 if(mMode == Mode.Viewing || mMode == Mode.Selecting)
        //                 {
        //                     MuPDFPageView cv = (MuPDFPageView)getSelectedView();
        //                     if(cv==null) return;
        //                     setMode(MuPDFReaderView.Mode.Selecting);
        //                         //Strangely getY() doesn't return the correct coordinate relative to the view on my device so we have to compute them ourselves from the getRaw methods. I hope this works on multiwindow devices...
        //                     int[] locationOnScreen = new int[2];
        //                     getLocationOnScreen(locationOnScreen);
                            
        //                     cv.selectText(longPressStartEvent.getX(),longPressStartEvent.getRawY()-locationOnScreen[1],longPressStartEvent.getX()+1,longPressStartEvent.getRawY()+1-locationOnScreen[1]);
        //                 }
        //             }   
        //         };
        //     longPressHandler.postDelayed(longPressed, android.view.ViewConfiguration.getLongPressTimeout());
        // }
        // if(event.getAction() == MotionEvent.ACTION_UP || ( (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_HOVER_MOVE) && longPressStartEvent != null && (Math.abs(longPressStartEvent.getX() - event.getX()) > 20 || Math.abs(longPressStartEvent.getY() - event.getY()) > 20) ))  //Must not use event.getX() here as this is not necessarially the current position of the pointer but can also be the start point of a swipe!
        // {
        //     Log.e("Pen", "startx="+longPressStartEvent.getX()+" curent="+event.getX());
            
        //     longPressHandler.removeCallbacks(longPressed);
        //     longPressStartEvent = null;
        // }



        
            // Now we process events to be interpreted as drawing or ereasing or as events that start drawing
            // 
            // By default use the first pointer
        int pointerIndexToUse = 0; 
            // If in stylus mode use the stylus istead
        if (mUseStylus)
        {
            int pointerIndexOfStylus = -1; 
            for(int pointerIndex = 0; pointerIndex < event.getPointerCount(); pointerIndex++) {
                if (event.getToolType(pointerIndex) == android.view.MotionEvent.TOOL_TYPE_STYLUS) {
                    pointerIndexOfStylus = pointerIndex;
                    break; // We simply take the first stylus we find.
                }
            }
            pointerIndexToUse = pointerIndexOfStylus; // is pointer index of stylus or -1 if no stylus event occured

                //Automaticall switch to drawing mode if touched with stylus in viewing mode
            if(mUseStylus && mMode == Mode.Viewing && event.getActionIndex() == pointerIndexToUse && event.getAction() == MotionEvent.ACTION_DOWN){
                
                Hit item = pageView.clickWouldHit(event);
                if(item != null && Hit.InkAnnotation.equals(item)){
                    pageView.passClickEvent(event);
                    pageView.editSelectedAnnotation();
//                    onHit(item);
                }
                else //if(item == null || Hit.Nothing.equals(item) )
                {
                    pageView.deselectAnnotation();
                    setMode(MuPDFReaderView.Mode.Drawing);
                }
            }
        }
        
        if (event.getActionIndex() == pointerIndexToUse || !mUseStylus)
        {
            final float x = event.getX(pointerIndexToUse);
            final float y = event.getY(pointerIndexToUse);
            
            if ( mMode == Mode.Drawing )
            {
                switch(event.getAction())
                {
                    case MotionEvent.ACTION_DOWN:
                        pageView.startDraw(x, y);
                        break;
                    case MotionEvent.ACTION_MOVE:
                            //First process "historical" coordinates that were "batched" into this event
                        final int historySize = event.getHistorySize();
                        for (int h = 0; h < historySize; h++) {
                            pageView.continueDraw(event.getHistoricalX(pointerIndexToUse,h), event.getHistoricalY(pointerIndexToUse,h));
                        }
                        pageView.continueDraw(x, y);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pageView.finishDraw();
                        onNumberOfStrokesChanged(pageView.getDrawingSize());
                        break;
                }
            }
            else if(mMode == Mode.Erasing)
            {
                switch(event.getAction())
                {
                    case MotionEvent.ACTION_DOWN:
                        pageView.startErase(x, y);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        pageView.continueErase(x, y);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        pageView.finishErase(x,y);
                        break;
                }
            }
        }
                
        if ((event.getAction() & event.getActionMasked()) == MotionEvent.ACTION_DOWN)
        {
            tapDisabled = false;
        }

        return super.onTouchEvent(event);
    }

    public void addSearchResult(SearchResult result) {
        SearchResults.put(result.getPageNumber(),result);
    }

    
    public void clearSearchResults() {
        SearchResults.clear();
    }

    public boolean hasSearchResults() {
        return SearchResults.size() !=0 ? true : false;
    }    

    public void goToNextSearchResult(int direction) {
        RectF resultRect = null;
        int resultPage = -1;
        SearchResult resultOnCurrentPage = SearchResults.get(getSelectedItemPosition());
        if(resultOnCurrentPage!=null && resultOnCurrentPage.incrementFocus(direction)) //There is a result on the current page in the right direction
        {
            resultRect = SearchResults.get(getSelectedItemPosition()).getFocusedSearchBox();
        }
        else 
        {
            for(int i = 0, size = SearchResults.size(); i < size; i++)
            {
                SearchResult result = SearchResults.valueAt(direction == 1 ? i : size-1-i);
                if(direction*result.getPageNumber() > direction*getSelectedItemPosition())
                {
                    if(direction == 1)
                        result.focusFirst();
                    else
                        result.focusLast();
                    resultPage = result.getPageNumber();
                    resultRect = result.getFocusedSearchBox();
                    break;
                };
            }
        }

        if(resultPage!=-1)
        {
            setDisplayedViewIndex(resultPage);
        }
        if(resultRect!=null)
        {
            doNextScrollWithCenter();
            setDocRelXScroll(resultRect.centerX());
            setDocRelYScroll(resultRect.centerY());
            resetupChildren();
        }
        else
        {
                //Notify user that more results might be coming if the searchTask ist still running
        }
    }
    
    
    @Override
    protected void onChildSetup(int i, View v) {
        if (SearchResults.get(i) != null)
            ((MuPDFView) v).setSearchResult(SearchResults.get(i));
        else
            ((MuPDFView) v).setSearchResult(null);

        ((MuPDFView) v).setLinkHighlighting(mLinksEnabled);

        ((MuPDFView) v).setChangeReporter(new Runnable() {
                public void run() {
                    applyToChildren(new ReaderView.ViewMapper() {
                            @Override
                            void applyToView(View view) {
                                ((MuPDFView) view).redraw(true);
                            }
                        });
                }
            });
    }

    @Override
    protected void onMoveOffChild(int i) {
        View v = getView(i);
        if (v != null)
            ((MuPDFView)v).deselectAnnotation();
    }

    @Override
    protected void onSettle(View v) {
//        Log.v("MuPDFReaderView", "onSettle("+v+")");
            // When the layout has settled ask the page to render in HQ
        ((MuPDFView) v).addHq(false);
    }

    @Override
    protected void onUnsettle(View v) {
            // When something changes making the previous settled view
            // no longer appropriate, tell the page to remove HQ
        ((MuPDFView) v).removeHq();
    }

    // @Override
    // protected void onNotInUse(View v) {
    //     ((MuPDFView) v).releaseResources();
    // }

    @Override
    protected void onScaleChild(View v, Float scale) {
        ((MuPDFView) v).setScale(scale);
    }


    @Override
    public Parcelable onSaveInstanceState() {
//        Log.v("MuPDFReaderView", "onSaveInstanceState() getSelectedView()="+getSelectedView());
        Bundle bundle = new Bundle();
        bundle.putParcelable("superInstanceState", super.onSaveInstanceState());
            //Save
        bundle.putString("mMode", mMode.toString());
        if(getSelectedView() != null) bundle.putParcelable("displayedViewInstanceState", ((PageView)getSelectedView()).onSaveInstanceState());
        
        return bundle;
    }
    
    @Override
    public void onRestoreInstanceState(Parcelable state) {
//        Log.v("MuPDFReaderView", "onRestoreInstanceState() getSelectedView()="+getSelectedView());      
        if (state instanceof Bundle) {
            Bundle bundle = (Bundle) state;
                //Load 
            mMode = Mode.valueOf(bundle.getString("mMode", mMode.toString()));
                //Save the displayedViewInstanceState for later if getSelectedView() returns null
            if(getSelectedView() != null)
                ((PageView)getSelectedView()).onRestoreInstanceState(bundle.getParcelable("displayedViewInstanceState"));
            else
                displayedViewInstanceState = bundle.getParcelable("displayedViewInstanceState");
            
            state = bundle.getParcelable("superInstanceState");
        }
        super.onRestoreInstanceState(state);
    }
}
