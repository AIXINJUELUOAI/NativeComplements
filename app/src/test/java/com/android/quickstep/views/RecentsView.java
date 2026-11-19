package com.android.quickstep.views;

/** Test stub. */
public class RecentsView extends android.widget.FrameLayout {
    public RecentsView(android.content.Context context){super(context);}
    public TaskView card;
    public int taskId;
    public TaskView getTaskViewByTaskId(int id){return id==taskId?card:null;}
}
