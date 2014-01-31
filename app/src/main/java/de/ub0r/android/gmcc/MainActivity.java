package de.ub0r.android.gmcc;

import org.jetbrains.annotations.NotNull;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import de.ub0r.android.logg0r.Log;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private static final String PREF_TARGET_CACHESIZE = "target_cache_size";

    private static final String MY_MUSIC_DB = "music.db";

    @SuppressLint("SdCardPath")
    private static final String GOOGLE_MUSIC_FILES
            = "/data/data/com.google.android.music/files/music";

    @SuppressLint("SdCardPath")
    private static final String GOOGLE_MUSIC_DATABASE
            = "/data/data/com.google.android.music/databases/music.db";

    @InjectView(R.id.current_cache_size)
    EditText mCurrentCacheSizeView;

    @InjectView(R.id.target_cache_size)
    EditText mTargetCacheSizeView;

    @InjectView(R.id.go)
    Button mGoButton;

    private int mCurrentCacheSize;

    public InputStream runAsRoot(final String... cmds) throws IOException {
        Process p = Runtime.getRuntime().exec("su");
        DataOutputStream os = new DataOutputStream(p.getOutputStream());
        for (String cmd : cmds) {
            Log.d(TAG, "running command: ", cmd);
            os.writeBytes(cmd);
            os.writeBytes("\n");
        }
        os.writeBytes("exit\n");
        os.flush();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        String l;
        while ((l = r.readLine()) != null) {
            Log.w(TAG, "error running command: ", l);
        }
        r.close();
        return p.getInputStream();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.inject(this);

        if (savedInstanceState == null) {
            int size = PreferenceManager.getDefaultSharedPreferences(this)
                    .getInt(PREF_TARGET_CACHESIZE, -1);
            if (size > 0) {
                mTargetCacheSizeView.setText(String.valueOf(size));
            } else {
                mTargetCacheSizeView.requestFocus();
            }
            updateData();
        } else {
            mCurrentCacheSize = savedInstanceState.getInt("mCurrentCacheSize");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.item_about:
                showAbout();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showAbout() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.about);
        b.setView(getLayoutInflater().inflate(R.layout.dialog_about, null));
        b.setPositiveButton(android.R.string.ok, null);
        b.show();
    }

    private void updateData() {
        String s = null;
        try {
            // get cache size
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    runAsRoot("du -m " + GOOGLE_MUSIC_FILES + " | cut -f 1")));
            s = r.readLine();
            if (s != null) {
                s = s.trim();
                mCurrentCacheSize = Integer.parseInt(s);
            }
            r.close();
            mCurrentCacheSizeView.setText(getString(R.string.cache_size, mCurrentCacheSize));
            File cacheDir = getCacheDir();
            assert cacheDir != null;
            if (!cacheDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            }

            // copy music.db
            File myMusicFile = getMyMusicFile();
            String myMusicDb = myMusicFile.getAbsolutePath();
            runAsRoot(new String[]{
                    "cp " + GOOGLE_MUSIC_DATABASE + " " + myMusicDb,
                    "chmod 644 " + myMusicDb
            }).close();
            mGoButton.setEnabled(true);
        } catch (IOException e) {
            Log.e(TAG, "IO error", e);
            Toast.makeText(this, getString(R.string.error_update, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            mGoButton.setEnabled(false);
        } catch (NumberFormatException e) {
            Log.e(TAG, "invalid number: ", s, e);
            Toast.makeText(this, getString(R.string.error_update, e.getMessage()),
                    Toast.LENGTH_LONG).show();
            mGoButton.setEnabled(false);
        }
    }

    private File getMyMusicFile() {
        return new File(getCacheDir(), MY_MUSIC_DB);
    }

    private SQLiteDatabase openMyMusicFile() {
        return SQLiteDatabase
                .openDatabase(getMyMusicFile().getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY);
    }

    @Override
    protected void onSaveInstanceState(@NotNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("mCurrentCacheSize", mCurrentCacheSize);
    }

    @OnClick(R.id.go)
    void onGoClick() {
        //noinspection ConstantConditions
        String s = mTargetCacheSizeView.getText().toString().trim();
        int targetCacheSize;

        // check input
        if (TextUtils.isEmpty(s)) {
            mTargetCacheSizeView.setError(getString(R.string.error_invalid_cacheSize));
            return;
        }

        // check input
        try {
            targetCacheSize = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            mTargetCacheSizeView.setError(getString(R.string.error_invalid_cacheSize));
            return;
        }

        // check input
        if (targetCacheSize > mCurrentCacheSize) {
            Log.i(TAG, "target > current", targetCacheSize, ">", mCurrentCacheSize);
            mTargetCacheSizeView.setError(getString(R.string.error_small_cacheSize));
            return;
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt(PREF_TARGET_CACHESIZE, targetCacheSize).apply();
        reduceCache(targetCacheSize);

        updateData();
    }

    void reduceCache(final int targetCacheSizeInMB) {
        long currentCacheSize = mCurrentCacheSize * 1024L * 1024L;
        long targetCacheSize = targetCacheSizeInMB * 1024L * 1024L;

        SQLiteDatabase db = openMyMusicFile();
        Cursor c = db.query("music",
                new String[]{"Id", "LocalCopySize", "LocalCopyPath", "Artist", "Title"},
                " LocalCopyPath not null and Id not in (select MusicId from shouldkeepon where KeepOnId = 1 )",
                null, null, null, "LastPlayDate ASC");
        Log.d(TAG, "#files: ", c.getCount());
        Log.d(TAG, "current cache size: ", currentCacheSize);
        Log.d(TAG, "target  cache size: ", targetCacheSize);
        while (c.moveToNext() && currentCacheSize > targetCacheSize) {
            long id = c.getLong(0);
            long size = c.getLong(1);
            String name = c.getString(2);
            String artist = c.getString(3);
            String title = c.getString(4);
            Log.i(TAG, "delete file: " +
                    id + "/" + name + "/ " + artist + " - " + title + " // size: " + size + "B");
            deleteCacheFile(name);
            currentCacheSize -= size;
        }
        Log.i(TAG, "final cache size: ", currentCacheSize / 1024 / 1024, "MB");
        c.close();
        db.close();
    }

    private void deleteCacheFile(final String name) {

        try {
            runAsRoot("rm " + GOOGLE_MUSIC_FILES + "/" + name).close();
        } catch (IOException e) {
            Log.e(TAG, "could not remove file: ", name, e);
        }
    }
}
