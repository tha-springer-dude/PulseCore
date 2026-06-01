package com.springer.pulseCore;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_SELECT_FOLDER = 1001;
    private static final String PREFS_NAME = "audio_player_prefs";
    private static final String KEY_FOLDER_URI = "folder_uri";

    private final List<AudioFile> audioFiles = new ArrayList<>();
    private AudioAdapter audioAdapter;
    private TextView nowPlayingText;
    private MediaPlayer mediaPlayer;
    private int playingPosition = AdapterView.INVALID_POSITION;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.setNavigationIcon(R.drawable.ic_menu_24);
        toolbar.setNavigationOnClickListener(this::showToolbarMenu);

        ListView audioList = findViewById(R.id.audioList);
        nowPlayingText = findViewById(R.id.nowPlayingText);
        updateNowPlayingText();

        audioAdapter = new AudioAdapter(this, audioFiles);
        audioList.setAdapter(audioAdapter);
        audioList.setOnItemClickListener((parent, view, position, id) -> playAudio(position));

        String savedFolderUri = getPreferences().getString(KEY_FOLDER_URI, null);
        if (savedFolderUri != null) {
            scanFolder(Uri.parse(savedFolderUri));
        }
    }

    private void showToolbarMenu(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        popupMenu.getMenu().add(R.string.select_music_folder);
        popupMenu.setOnMenuItemClickListener(this::onToolbarMenuItemClick);
        popupMenu.show();
    }

    private boolean onToolbarMenuItemClick(MenuItem item) {
        openFolderPicker();
        return true;
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_SELECT_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SELECT_FOLDER || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }

        Uri folderUri = data.getData();
        if (folderUri == null) {
            return;
        }

        int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        getContentResolver().takePersistableUriPermission(folderUri, flags);
        getPreferences().edit().putString(KEY_FOLDER_URI, folderUri.toString()).apply();
        stopPlayback();
        scanFolder(folderUri);
    }

    private void scanFolder(Uri folderUri) {
        audioFiles.clear();
        scanTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri));
        audioAdapter.notifyDataSetChanged();
    }

    private void scanTree(Uri treeUri, String documentId) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return;
            }

            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (cursor.moveToNext()) {
                String childDocumentId = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mimeType = cursor.getString(mimeColumn);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    scanTree(treeUri, childDocumentId);
                } else if (isAudioFile(name, mimeType)) {
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId);
                    audioFiles.add(new AudioFile(name, fileUri));
                }
            }
        } catch (SecurityException exception) {
            Toast.makeText(this, "Cannot read selected folder", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAudioFile(String name, String mimeType) {
        if (mimeType != null && mimeType.startsWith("audio/")) {
            return true;
        }

        String lowerName = name == null ? "" : name.toLowerCase();
        return lowerName.endsWith(".mp3")
                || lowerName.endsWith(".wav")
                || lowerName.endsWith(".m4a")
                || lowerName.endsWith(".aac")
                || lowerName.endsWith(".flac")
                || lowerName.endsWith(".ogg")
                || lowerName.endsWith(".opus")
                || lowerName.endsWith(".wma");
    }

    private void playAudio(int position) {
        stopPlayback();
        AudioFile audioFile = audioFiles.get(position);

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, audioFile.uri);
            mediaPlayer.setOnCompletionListener(player -> stopPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            playingPosition = position;
            updateNowPlayingText();
            audioAdapter.notifyDataSetChanged();
        } catch (IOException | RuntimeException exception) {
            stopPlayback();
            Toast.makeText(this, "Cannot play file", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            mediaPlayer.setOnCompletionListener(null);
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        playingPosition = AdapterView.INVALID_POSITION;
        updateNowPlayingText();
        if (audioAdapter != null) {
            audioAdapter.notifyDataSetChanged();
        }
    }

    private void updateNowPlayingText() {
        if (nowPlayingText == null) {
            return;
        }

        if (playingPosition == AdapterView.INVALID_POSITION) {
            nowPlayingText.setText("No track playing");
        } else {
            nowPlayingText.setText("Playing: " + audioFiles.get(playingPosition).name);
        }
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }

    private SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static class AudioFile {
        final String name;
        final Uri uri;

        AudioFile(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    private class AudioAdapter extends ArrayAdapter<AudioFile> {
        AudioAdapter(Context context, List<AudioFile> files) {
            super(context, android.R.layout.simple_list_item_1, files);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView textView = view.findViewById(android.R.id.text1);
            AudioFile audioFile = getItem(position);
            textView.setText(audioFile == null ? "" : audioFile.name);
            view.setBackgroundColor(position == playingPosition ? Color.LTGRAY : Color.TRANSPARENT);
            return view;
        }
    }
}
