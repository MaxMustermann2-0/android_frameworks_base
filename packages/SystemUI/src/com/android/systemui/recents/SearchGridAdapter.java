package com.android.systemui.recents;


import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class SearchGridAdapter extends BaseAdapter {

    private Context mContext;
    private ArrayList<SearchItem> mItems;

    public SearchGridAdapter(Context context, ArrayList<SearchItem> items) {
        mContext = context;
        mItems = items;
    }

    @Override
    public int getCount() {
        return mItems.size();
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
        final View itemView = inflater.inflate(R.layout.recents_search_grid_item, null);
        TextView labelTv = (TextView) itemView.findViewById(R.id.label);
        TextView subLabelTv = (TextView) itemView.findViewById(R.id.sub_label);
        final ImageView iv = (ImageView) itemView.findViewById(R.id.icon);
        iv.setTag(mItems.get(i).getLaunchInfo());
        labelTv.setText(mItems.get(i).getLabel());
        subLabelTv.setText(mItems.get(i).getSubLabel());
        switch (mItems.get(i).getType()) {
            case SearchItem.TYPE_APPLICATION:
                iv.setImageResource(R.drawable.ic_application);
                LoadApplicationImageTask task = new LoadApplicationImageTask();
                task.imageView = iv;
                task.packageName = mItems.get(i).getLaunchInfo();
                task.activityName = mItems.get(i).getMoreLaunchInfo();
                task.execute();
                break;
            case SearchItem.TYPE_CONTACT:
                iv.setImageResource(R.drawable.ic_contact);
                LoadContactImageTask contactTask = new LoadContactImageTask();
                contactTask.imageView = iv;
                contactTask.id = mItems.get(i).getLaunchInfo();
                contactTask.execute();
                break;
            case SearchItem.TYPE_MUSIC:
                iv.setImageResource(R.drawable.ic_music);
                LoadMusicImageTask musicTask = new LoadMusicImageTask();
                musicTask.imageView = iv;
                musicTask.id = mItems.get(i).getLaunchInfo();
                musicTask.execute();
                break;
            case SearchItem.TYPE_CALENDAR:
                iv.setImageResource(R.drawable.ic_calendar);
                break;
            case SearchItem.TYPE_FILE:
                iv.setImageResource(mItems.get(i).getMoreLaunchInfo().equals("file") ?
                        getIconForFileExtension(getExtension(mItems.get(i).getLaunchInfo()))
                        : R.drawable.ic_folder);
                String mime = getMimeType(mItems.get(i).getLaunchInfo());
                if (mime != null && mime.startsWith("image")) {
                    LoadThumbnailTask thumbnailTask = new LoadThumbnailTask();
                    thumbnailTask.imageView = iv;
                    thumbnailTask.path = mItems.get(i).getLaunchInfo();
                    thumbnailTask.execute();
                }
                break;
        }
        itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent();
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                switch (mItems.get(i).getType()) {
                    case SearchItem.TYPE_APPLICATION:
                        intent.setComponent(new ComponentName(mItems.get(i).getLaunchInfo(),
                                mItems.get(i).getMoreLaunchInfo()));

                        break;
                    case SearchItem.TYPE_CONTACT:
                        intent.setAction(Intent.ACTION_VIEW);
                        intent.setData(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI,
                                mItems.get(i).getLaunchInfo()));
                        break;
                    case SearchItem.TYPE_MUSIC:
                        intent.setAction(Intent.ACTION_VIEW);
                        intent.setDataAndType(ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                Long.parseLong(mItems.get(i).getLaunchInfo())), "audio/*");
                        break;
                    case SearchItem.TYPE_CALENDAR:
                        intent.setAction(Intent.ACTION_VIEW);
                        intent.setData(ContentUris.withAppendedId(
                                CalendarContract.Events.CONTENT_URI,
                                Long.parseLong(mItems.get(i).getLaunchInfo())));
                        break;
                    case SearchItem.TYPE_FILE:
                        intent.setAction(Intent.ACTION_VIEW);
                        File file = new File(mItems.get(i).getLaunchInfo());
                        Log.d("AndroidRuntime", mItems.get(i).getLaunchInfo());
                        intent.setDataAndType(Uri.fromFile(file), mItems.get(i).getMoreLaunchInfo()
                                .equals("file") ? getMimeType(mItems.get(i).getLaunchInfo())
                                : "resource/folder");
                        break;

                }
                int left = 0, top = 0;
                int width = itemView.getMeasuredWidth(), height = itemView.getMeasuredHeight();
                Rect bounds = iv.getDrawable().getBounds();
                left = (width - bounds.width()) / 2;
                top = itemView.getPaddingTop();
                width = bounds.width();
                height = bounds.height();
                ActivityOptions options = ActivityOptions.makeClipRevealAnimation(itemView, left,
                        top, width, height);
                try {
                    mContext.startActivity(intent, options.toBundle());
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(mContext, R.string.no_application, Toast.LENGTH_SHORT).show();
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

    public static String getMimeType(String fileName) {
        String extension = getExtension(fileName);
        if (extension == null) return null;
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "*/*" : mime;
    }

    public static String getExtension(String fileName) {
        int extensionDelimiter = fileName.lastIndexOf(".");
        if (extensionDelimiter == -1) return null;
        return fileName.substring(extensionDelimiter + 1, fileName.length());
    }

    private static int getIconForFileExtension(String extension) {
        if (extension == null || extension.length() == 0) return R.drawable.ic_file;
        switch (extension) {
            case "doc":
            case "docx":
            case "odt":
            case "rtf":
                return R.drawable.ic_file_document;
            case "ppt":
            case "pptx":
            case "odp":
                return R.drawable.ic_file_presentation;
            case "xls":
            case "xlsx":
            case "ods":
                return R.drawable.ic_file_calc;
            case "pdf":
                return R.drawable.ic_file_pdf;
            case "zip":
            case "tar":
            case "gz":
            case "bz2":
            case "rar":
            case "jar":
            case "xz":
            case "7z":
                return R.drawable.ic_file_archive;
            case "mp3":
            case "ogg":
            case "wav":
            case "wma":
            case "flac":
                return R.drawable.ic_file_music;
            case "mp4":
            case "mpeg":
            case "mpg":
            case "avi":
            case "wmv":
                return R.drawable.ic_file_movie;
            case "apk":
                return R.drawable.ic_file_app;
            case "txt":
                return R.drawable.ic_file_text;
            case "java":
            case "js":
            case "cpp":
            case "cc":
            case "c":
            case "cs":
            case "htm":
            case "html":
            case "xml":
                return R.drawable.ic_file_xml;
            case "png":
            case "jpg":
            case "gif":
            case "jpeg":
                return R.drawable.ic_file_pic;
        }
        return R.drawable.ic_file;
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

    private class LoadContactImageTask extends AsyncTask<Object, Object, Bitmap> {

        String id;
        ImageView imageView;

        @Override
        protected Bitmap doInBackground(Object... params) {
            Uri uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI,
                    Long.parseLong(id));
            ContentResolver cr = mContext.getContentResolver();
            InputStream input = ContactsContract.Contacts.openContactPhotoInputStream(cr, uri);
            if (input == null) {
                return null;
            }
            return BitmapFactory.decodeStream(input);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            if (bitmap != null && imageView != null && imageView.getTag().equals(id))
                imageView.setImageBitmap(bitmap);
        }
    }

    private class LoadMusicImageTask extends AsyncTask<Object, Object, Bitmap> {

        String id;
        ImageView imageView;

        @Override
        protected Bitmap doInBackground(Object... params) {
            int size = mContext.getResources().getDimensionPixelSize(R.dimen.search_grid_icon_size);
            String selection = MediaStore.Audio.Media._ID + " = " + id + "";

            Cursor cursor = mContext.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[]{
                            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.ALBUM_ID},
                    selection, null, null);

            if (cursor.moveToFirst()) {
                long albumId = cursor.getLong(cursor
                        .getColumnIndex(MediaStore.Audio.Media.ALBUM_ID));

                Uri artworkUri = Uri.parse("content://media/external/audio/albumart");
                Uri uri = ContentUris.withAppendedId(artworkUri, albumId);
                cursor.close();
                try {
                    return Bitmap.createScaledBitmap(MediaStore.Images.Media.getBitmap(
                            mContext.getContentResolver(), uri), size, size, false);
                } catch (IOException | NullPointerException e) {
                    return null;
                }
            }
            cursor.close();
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            if (bitmap != null && imageView != null && imageView.getTag().equals(id))
                imageView.setImageBitmap(bitmap);
        }
    }

    private class LoadThumbnailTask extends AsyncTask<Object, Object, Bitmap> {

        String path;
        ImageView imageView;

        @Override
        protected Bitmap doInBackground(Object... params) {
            int size = mContext.getResources().getDimensionPixelSize(R.dimen.search_grid_icon_size);
            try {
                return Bitmap.createScaledBitmap(BitmapFactory.decodeFile(path), size, size, false);
            } catch (NullPointerException e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            if (bitmap != null && imageView != null && imageView.getTag().equals(path))
                imageView.setImageBitmap(bitmap);
        }
    }
}