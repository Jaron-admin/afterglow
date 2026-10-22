package com.oldphone.ipcam;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Wrapping row for trigger chips. Children keep their own width. */
final class FlowRow extends ViewGroup {
    private final int gapX;
    private final int gapY;

    FlowRow(Context context, int gapX, int gapY) {
        super(context);
        this.gapX = gapX;
        this.gapY = gapY;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int max = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int x = 0;
        int y = getPaddingTop();
        int row = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int childHeight = child.getLayoutParams() == null ? -1 : child.getLayoutParams().height;
            int heightSpec = childHeight > 0
                    ? MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec);
            int childWidth = child.getMeasuredWidth();
            if (x > 0 && x + childWidth > max) {
                y += row + gapY;
                x = 0;
                row = 0;
            }
            x += childWidth + gapX;
            row = Math.max(row, child.getMeasuredHeight());
        }
        setMeasuredDimension(width, y + row + getPaddingBottom());
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int max = right - left - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int row = 0;
        int lineStart = 0;
        for (int i = 0; i <= getChildCount(); i++) {
            View child = i < getChildCount() ? getChildAt(i) : null;
            if (child != null && child.getVisibility() == GONE) continue;
            int childWidth = child == null ? 0 : child.getMeasuredWidth();
            if (child == null || (x > getPaddingLeft() && x - getPaddingLeft() + childWidth > max)) {
                int lineY = y;
                int cursor = getPaddingLeft();
                for (int j = lineStart; j < i; j++) {
                    View placed = getChildAt(j);
                    if (placed.getVisibility() == GONE) continue;
                    int height = placed.getMeasuredHeight();
                    int offset = Math.max(0, (row - height) / 2);
                    placed.layout(cursor, lineY + offset, cursor + placed.getMeasuredWidth(), lineY + offset + height);
                    cursor += placed.getMeasuredWidth() + gapX;
                }
                y += row + gapY;
                x = getPaddingLeft();
                row = 0;
                lineStart = i;
            }
            if (child == null) break;
            x += childWidth + gapX;
            row = Math.max(row, child.getMeasuredHeight());
        }
    }
}
