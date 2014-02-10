package de.ub0r.android.gmcc;

import org.jetbrains.annotations.NotNull;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.OnClick;
import de.ub0r.android.logg0r.Log;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private static final String PREF_TARGET_CACHE_SIZE = "target_cache_size";

    private static final String MY_MUSIC_DB = "music.db";

    @SuppressLint("SdCardPath")
    private static final String[] GOOGLE_MUSIC_DIRS = new String[]{
            "/data/data/com.google.android.music/files/music/",
            "/storage/sdcard0/Android/data/com.google.android.music/files/music/",
            "/storage/sdcard1/Android/data/com.google.android.music/files/music/",
            "/storage/sdcard2/Android/data/com.google.android.music/files/music/",
    };

    @SuppressLint("SdCardPath")
    private static final String GOOGLE_MUSIC_DATABASE
            = "/data/data/com.google.android.music/databases/music.db";

    private static final Pattern DF_PATTERN = Pattern.compile("^[^ ]+ +[^ ]+ +[^ ]+ +([^ ]+)");

    @InjectView(R.id.current_cache_size)
    EditText mCurrentCacheSizeView;

    @InjectView(R.id.target_cache_size)
    EditText mTargetCacheSizeView;

    @InjectView(R.id.go)
    Button mGoButton;

    private String mMusicDir;

    private boolean mIsInternalMusicDir;

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
        mTargetCacheSizeView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count,
                    final int after) {
                // nothing to do
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int count,
                    final int after) {
                // nothing to do
            }

            @Override
            public void afterTextChanged(final Editable s) {
                updateGoButtonState();
            }
        });

        if (savedInstanceState == null) {
            int size = PreferenceManager.getDefaultSharedPreferences(this)
                    .getInt(PREF_TARGET_CACHE_SIZE, -1);
            if (size > 0) {
                mTargetCacheSizeView.setText(String.valueOf(size));
            } else {
                mTargetCacheSizeView.requestFocus();
            }
            mMusicDir = getMusicDir();
            Log.i(TAG, "music dir: ", mMusicDir);
            updateData();
        } else {
            mCurrentCacheSize = savedInstanceState.getInt("mCurrentCacheSize");
            mMusicDir = savedInstanceState.getString("mMusicDir");
        }
        updateGoButtonState();
        assert mMusicDir != null;
        mIsInternalMusicDir = mMusicDir.equals(GOOGLE_MUSIC_DIRS[0]);
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

    private String getMusicDir() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            for (int i = 1; i < GOOGLE_MUSIC_DIRS.length; ++i) {
                String dir = GOOGLE_MUSIC_DIRS[i];
                File f = new File(dir);
                if (!f.exists() || !f.isDirectory()) {
                    continue;
                }
                if (f.list().length == 0) {
                    continue;
                }
                return dir;
            }
        }
        return GOOGLE_MUSIC_DIRS[0];
    }

    private void updateData() {
        String s = null;
        try {
            // get cache size
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    runAsRoot("du -m " + mMusicDir + " | cut -f 1")));
            s = r.readLine();
            if (s != null) {
                s = s.trim();
                mCurrentCacheSize = Integer.parseInt(s);
            }
            r.close();
            mCurrentCacheSizeView.setText(getString(R.string.cache_size, mCurrentCacheSize));

            // get free space
            r = new BufferedReader(new InputStreamReader(
                    runAsRoot("df " + mMusicDir + " | grep /")));
            String line = r.readLine();
            Matcher matcher = DF_PATTERN.matcher(line);
            if (matcher.find()) {
                int free;
                line = matcher.group(1).trim();
                Log.d(TAG, "free: ", line);
                try {
                    if (line.endsWith("G")) {
                        free = (int) (Float.parseFloat(line.substring(0, line.length() - 1))
                                * 1024);
                    } else if (line.endsWith("M")) {
                        free = (int) Float.parseFloat(line.substring(0, line.length() - 1));
                    } else if (line.endsWith("k")) {
                        free = (int) (Float.parseFloat(line.substring(0, line.length() - 1))
                                / 1024);
                    } else {
                        free = 0;
                    }
                    mCurrentCacheSizeView
                            .setText(getString(R.string.cache_size_free, mCurrentCacheSize, free));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "invalid number: " + line, e);
                }
            }
            r.close();

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
        outState.putString("mMusicDir", mMusicDir);
    }

    private int getTargetCacheSize() {
        //noinspection ConstantConditions
        String s = mTargetCacheSizeView.getText().toString().trim();
        int targetCacheSize;

        // check input
        if (TextUtils.isEmpty(s)) {
            return -1;
        }

        // check input
        try {
            targetCacheSize = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            mTargetCacheSizeView.setError(getString(R.string.error_invalid_cacheSize));
            return -1;
        }

        return targetCacheSize;
    }

    private void updateGoButtonState() {
        mGoButton.setEnabled(getTargetCacheSize() < mCurrentCacheSize);
    }

    @OnClick(R.id.go)
    void onGoClick() {
        int targetCacheSize = getTargetCacheSize();

        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putInt(PREF_TARGET_CACHE_SIZE, targetCacheSize).apply();
        reduceCache(targetCacheSize);

        updateData();
        Toast.makeText(this, R.string.done, Toast.LENGTH_LONG).show();
    }

    void reduceCache(final int targetCacheSizeInMB) {
        long currentCacheSize = mCurrentCacheSize * 1024L * 1024L;
        long targetCacheSize = targetCacheSizeInMB * 1024L * 1024L;

        SQLiteDatabase db = openMyMusicFile();
        Cursor c = db.query("music",
                new String[]{"Id", "LocalCopySize", "LocalCopyPath", "Artist", "Title"},
                "LocalCopyPath not null and "
                        + "Rating != 5 and not ("
                        + "AlbumId in (select AlbumId from keepon where AlbumId not null) or "
                        + "ArtistId in  (select ArtistId from keepon where ArtistId not null) or "
                        + "Id in (select MusicId from listitems where ListId in (select ListId from keepon where ListId not null)))",
                null, null, null, "Rating DESC, LastPlayDate ASC");
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
                    id + "/" + name + "/ " + artist + " - " + title + " // size: " + (size / 1024
                    / 1024) + "MB");
            deleteCacheFile(name);
            currentCacheSize -= size;
        }
        Log.i(TAG, "final cache size: ", currentCacheSize / 1024 / 1024, "MB");
        c.close();
        db.close();
    }

    private void deleteCacheFile(final String name) {
        String path = mMusicDir + name;
        Log.d(TAG, "deleteCacheFile(", path, ")");
        if (mIsInternalMusicDir || !new File(path).delete()) {
            try {
                runAsRoot("rm " + path).close();
            } catch (IOException e) {
                Log.e(TAG, "could not remove file: ", path, e);
            }
        }
    }
}
