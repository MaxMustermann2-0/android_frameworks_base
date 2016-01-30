package android.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.R;

public class BootProgressDialog extends ProgressDialog {

    private String mTitle;
    private CharSequence mMessage;

    private int mProgressVal;

    private ProgressBar mProgress;
    private TextView mMessageView;
    private TextView mTitleView;

    private Handler mViewUpdateHandler;


    public BootProgressDialog(Context context, int theme, String title, CharSequence message,
                              int progressVal) {
        super(context, theme);
        mTitle = title;
        mMessage = message;
        mProgressVal = progressVal;

    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        setContentView(inflater.inflate(com.android.internal.R.layout.boot_progress_dialog, null));
        mViewUpdateHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
            }
        };
        mProgress = (ProgressBar) findViewById(R.id.progress);
        mMessageView = (TextView) findViewById(R.id.message);
        mTitleView = (TextView) findViewById(R.id.title);
        mMessageView.setText(mMessage);
        mTitleView.setText(mTitle);
        if (mProgressVal == -1) {
            mProgress.setIndeterminate(true);
        } else {
            mProgress.setIndeterminate(false);
            mProgress.setProgress(mProgressVal);
        }
    }

    @Override
    public void setProgress(int value) {
        mProgressVal = value;
        if (mProgressVal == -1) {
            mProgress.setIndeterminate(true);
        } else {
            mProgress.setIndeterminate(false);
            mProgress.setProgress(mProgressVal);
        }
    }

    @Override
    public void setMessage(CharSequence message) {
        mMessage = message;
        mMessageView.setText(message);
    }

    @Override
    public void setTitle(CharSequence title) {
        mTitle = title.toString();
        mTitleView.setText(mTitle);
    }
}