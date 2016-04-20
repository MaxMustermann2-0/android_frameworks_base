package com.android.systemui.recents;


import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;

import java.util.ArrayList;

public class DashGridAdapter extends BaseAdapter {

    private Context mContext;
    private ArrayList<DashItem> mItems;

    public DashGridAdapter(Context context, ArrayList<DashItem> items) {
        mContext = context;
        mItems = items;
    }

    @Override
    public int getCount() {
        return mItems == null ? 0 : mItems.size();
    }

    @Override
    public Object getItem(int i) {
        return null;
    }

    @Override
    public long getItemId(int i) {
        return 0;
    }

    @Override
    public View getView(final int i, View view, ViewGroup viewGroup) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        final View itemView = inflater.inflate(R.layout.recents_dash_grid_item, null);
        TextView tv = (TextView) itemView.findViewById(R.id.label);
        final ImageView iv = (ImageView) itemView.findViewById(R.id.icon);
        iv.setTag(mItems.get(i).getLaunchInfo());
        tv.setText(mItems.get(i).getLabel());
        iv.setImageResource(R.drawable.ic_application);
        if (mItems.get(i).getType() == DashItem.TYPE_APPLICATION) {
            LoadApplicationImageTask task = new LoadApplicationImageTask();
            task.imageView = iv;
            task.packageName = mItems.get(i).getLaunchInfo();
            task.activityName = mItems.get(i).getMoreLaunchInfo();
            task.execute();
        }
        itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mItems.get(i).getType() == DashItem.TYPE_APPLICATION) {
                    Intent intent = new Intent();
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(new ComponentName(mItems.get(i).getLaunchInfo(),
                            mItems.get(i).getMoreLaunchInfo()));
                    int left = 0, top = 0;
                    int width = itemView.getMeasuredWidth(), height = itemView.getMeasuredHeight();
                    Rect bounds = iv.getDrawable().getBounds();
                    left = (width - bounds.width()) / 2;
                    top = itemView.getPaddingTop();
                    width = bounds.width();
                    height = bounds.height();
                    ActivityOptions options = ActivityOptions.makeClipRevealAnimation(itemView, left, top, width, height);
                    mContext.startActivity(intent, options.toBundle());
                } else {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setData(Uri.parse(mItems.get(i).getLaunchInfo()));
                        mContext.startActivity(intent);
                    } catch (ActivityNotFoundException ex) {
                        Toast.makeText(mContext, R.string.no_application, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
        itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                itemView.startDrag(ClipData.newPlainText("", ""),
                        new View.DragShadowBuilder(itemView), mItems.get(i), 0);
                return true;
            }
        });
        return itemView;
    }

    private class LoadApplicationImageTask extends AsyncTask<Object, Object, Drawable> {

        String packageName;
        String activityName;
        ImageView imageView;

        @Override
        protected Drawable doInBackground(Object... params) {
            PackageManager packageManager = mContext.getPackageManager();
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(packageName, activityName));
            ResolveInfo resolveInfo = packageManager.resolveActivity(intent, 0);
            return resolveInfo == null ? null : resolveInfo.loadIcon(packageManager);
        }

        @Override
        protected void onPostExecute(Drawable drawable) {
            super.onPostExecute(drawable);
            if (imageView != null && imageView.getTag().equals(packageName))
                imageView.setImageDrawable(drawable);
        }
    }
}
