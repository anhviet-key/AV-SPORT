package com.anhviet.avsport;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.anhviet.avsport.data.ApiClient;
import com.anhviet.avsport.model.StreamOption;
import com.anhviet.avsport.model.StreamResult;
import com.anhviet.avsport.ui.Ui;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {
    private static final int MAX_AUTO_STREAM_INDEX = 8;
    private static final int[] ZOOM_MODES = {
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    };
    private static final String[] ZOOM_LABELS = {"Vừa khung", "Phóng", "Lấp đầy"};

    private final ApiClient apiClient = new ApiClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout overlay;
    private LinearLayout controls;
    private ProgressBar progressBar;
    private LinearLayout streamRow;
    private TextView titleView;
    private TextView errorView;
    private Button zoomButton;
    private Button hideButton;

    private String pageUrl;
    private String title;
    private ArrayList<String> fallbackPageUrls = new ArrayList<>();
    private int fallbackPageIndex;
    private StreamResult currentStream;
    private int selectedStreamIndex;
    private int zoomModeIndex;
    private boolean overlayVisible;
    private String lastPlaybackUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        pageUrl = getIntent().getStringExtra(MainActivity.EXTRA_CARD_PAGE_URL);
        ArrayList<String> extras = getIntent().getStringArrayListExtra(MainActivity.EXTRA_CARD_FALLBACK_URLS);
        if (extras != null) {
            fallbackPageUrls.addAll(extras);
        }
        if (pageUrl != null && pageUrl.length() > 0 && !fallbackPageUrls.contains(pageUrl)) {
            fallbackPageUrls.add(0, pageUrl);
        }
        title = getIntent().getStringExtra(MainActivity.EXTRA_CARD_TITLE);
        if (title == null || title.length() == 0) {
            title = "Trực tiếp";
        }

        buildLayout();
        loadStream(0, true);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (player != null) {
            player.release();
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (overlayVisible) {
                setOverlayVisible(false);
                return true;
            }
            finish();
            return true;
        }

        if (overlayVisible) {
            if (isConfirmKey(keyCode) && getCurrentFocus() == overlay) {
                setOverlayVisible(false);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (isConfirmKey(keyCode)) {
            setOverlayVisible(true);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            changeStream(1);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            changeStream(-1);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            changeZoom(1);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            changeZoom(-1);
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
            || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_SPACE
            || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(root);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(ZOOM_MODES[zoomModeIndex]);
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                handleStreamError(error.getMessage(), true);
            }
        });
        playerView.setPlayer(player);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 76), Gravity.CENTER);
        root.addView(progressBar, progressParams);

        overlay = new FrameLayout(this);
        overlay.setFocusable(true);
        overlay.setClickable(true);
        overlay.setBackgroundColor(0x3302070D);
        overlay.setOnClickListener(view -> setOverlayVisible(false));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        controls.setClickable(true);
        overlay.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = actionButton("Quay lại");
        back.setOnClickListener(view -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 58)));

        titleView = Ui.text(this, title, 24, Ui.TEXT, Typeface.BOLD);
        titleView.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.setMargins(Ui.dp(this, 18), 0, 0, 0);
        top.addView(titleView, titleParams);

        hideButton = actionButton("Ẩn điều khiển");
        hideButton.setOnClickListener(view -> setOverlayVisible(false));
        styleControlButton(hideButton, Ui.GREEN);
        LinearLayout.LayoutParams hideParams = new LinearLayout.LayoutParams(Ui.dp(this, 180), Ui.dp(this, 58));
        hideParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
        top.addView(hideButton, hideParams);

        zoomButton = actionButton("Khung hình: " + ZOOM_LABELS[zoomModeIndex]);
        zoomButton.setOnClickListener(view -> changeZoom(1));
        styleControlButton(zoomButton, Ui.CARD_FOCUS);
        LinearLayout.LayoutParams zoomParams = new LinearLayout.LayoutParams(Ui.dp(this, 230), Ui.dp(this, 58));
        zoomParams.setMargins(Ui.dp(this, 12), 0, 0, 0);
        top.addView(zoomButton, zoomParams);

        controls.addView(top);

        View spacer = new View(this);
        controls.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18));
        bottom.setBackground(Ui.rounded(Ui.PANEL, 18, 1, Ui.BORDER_SOFT, this));
        controls.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        errorView = Ui.text(this, "", 16, Ui.TEXT, Typeface.BOLD);
        errorView.setVisibility(View.GONE);
        bottom.addView(errorView);

        streamRow = new LinearLayout(this);
        streamRow.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView streamScroller = new HorizontalScrollView(this);
        streamScroller.setHorizontalScrollBarEnabled(false);
        streamScroller.addView(streamRow, new HorizontalScrollView.LayoutParams(-2, -1));
        LinearLayout.LayoutParams streamParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 66));
        streamParams.setMargins(0, Ui.dp(this, 12), 0, 0);
        bottom.addView(streamScroller, streamParams);

        setOverlayVisible(false);
    }

    private Button actionButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(Ui.TEXT);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        Ui.applyFocusBackground(button, Ui.CARD, Ui.CARD_FOCUS);
        return button;
    }

    private void styleControlButton(Button button, int accentColor) {
        button.setBackground(Ui.rounded(Ui.CARD, 14, 2, accentColor, this));
        button.setOnFocusChangeListener((view, hasFocus) -> view.setBackground(Ui.rounded(
            hasFocus ? accentColor : Ui.CARD,
            14,
            3,
            hasFocus ? Ui.YELLOW : accentColor,
            this
        )));
    }

    private void stylePlayerButton(Button button, boolean active) {
        int normalColor = active ? Ui.RED : Ui.CARD;
        int normalBorder = active ? Ui.RED : Ui.BORDER;
        button.setBackground(Ui.rounded(normalColor, 14, active ? 3 : 2, normalBorder, this));
        button.setOnFocusChangeListener((view, hasFocus) -> view.setBackground(Ui.rounded(
            hasFocus ? Ui.GREEN : normalColor,
            14,
            hasFocus || active ? 4 : 2,
            hasFocus ? Ui.YELLOW : normalBorder,
            this
        )));
    }

    private void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
        overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            overlay.post(this::focusSelectedStreamButton);
        }
    }

    private void focusSelectedStreamButton() {
        for (int index = 0; index < streamRow.getChildCount(); index++) {
            View child = streamRow.getChildAt(index);
            Object tag = child.getTag();
            if (tag instanceof Integer && ((Integer) tag) == selectedStreamIndex) {
                child.requestFocus();
                return;
            }
        }
        if (zoomButton != null) {
            zoomButton.requestFocus();
        }
    }

    private void loadStream(int streamIndex, boolean autoRetry) {
        selectedStreamIndex = Math.max(0, streamIndex);
        int requestedIndex = selectedStreamIndex;
        progressBar.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        executor.execute(() -> {
            try {
                StreamResult stream = apiClient.fetchStream(pageUrl, requestedIndex);
                String playbackUrl = apiClient.toAbsoluteUrl(stream.bestUrl());
                runOnUiThread(() -> playStream(stream, playbackUrl, requestedIndex));
            } catch (Exception error) {
                runOnUiThread(() -> handleStreamError(error.getMessage(), autoRetry));
            }
        });
    }

    private void playStream(StreamResult stream, String playbackUrl, int requestedIndex) {
        currentStream = stream;
        selectedStreamIndex = stream.selectedStreamIndex > 0 ? stream.selectedStreamIndex : requestedIndex;
        lastPlaybackUrl = playbackUrl;
        if (playbackUrl == null || playbackUrl.length() == 0) {
            handleStreamError("Luồng rỗng.", true);
            return;
        }
        titleView.setText(stream.title == null || stream.title.length() == 0 ? title : stream.title);
        renderStreamOptions();
        progressBar.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);

        player.stop();
        player.clearMediaItems();
        player.setMediaItem(MediaItem.fromUri(playbackUrl));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void handleStreamError(String message, boolean autoRetry) {
        progressBar.setVisibility(View.GONE);
        int knownMaxIndex = currentStream == null ? MAX_AUTO_STREAM_INDEX : Math.max(MAX_AUTO_STREAM_INDEX, currentStream.options.size() - 1);
        if (autoRetry && selectedStreamIndex < knownMaxIndex) {
            loadStream(selectedStreamIndex + 1, true);
            return;
        }

        if (autoRetry && switchToNextFallbackPage()) {
            return;
        }

        errorView.setVisibility(View.VISIBLE);
        errorView.setText("Không phát được luồng này. " + (message == null ? "" : message));
        setOverlayVisible(true);
        Toast.makeText(this, "Không phát được luồng hiện tại.", Toast.LENGTH_LONG).show();
    }

    private boolean switchToNextFallbackPage() {
        if (fallbackPageUrls == null || fallbackPageIndex >= fallbackPageUrls.size() - 1) {
            return false;
        }

        fallbackPageIndex += 1;
        pageUrl = fallbackPageUrls.get(fallbackPageIndex);
        selectedStreamIndex = 0;
        currentStream = null;
        lastPlaybackUrl = null;
        Toast.makeText(this, "Đang thử nguồn dự phòng " + (fallbackPageIndex + 1) + "/" + fallbackPageUrls.size(), Toast.LENGTH_SHORT).show();
        loadStream(0, true);
        return true;
    }

    private void renderStreamOptions() {
        streamRow.removeAllViews();
        if (currentStream == null) {
            return;
        }

        for (StreamOption option : currentStream.options) {
            Button button = actionButton(option.label);
            boolean active = option.index == selectedStreamIndex;
            button.setTag(option.index);
            stylePlayerButton(button, active);
            button.setOnClickListener(view -> loadStream(option.index, true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 140), Ui.dp(this, 56));
            params.setMargins(0, 0, Ui.dp(this, 12), 0);
            streamRow.addView(button, params);
        }

        Button external = actionButton("Mở ngoài");
        stylePlayerButton(external, false);
        external.setOnClickListener(view -> openExternal());
        LinearLayout.LayoutParams externalParams = new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 56));
        externalParams.setMargins(Ui.dp(this, 6), 0, 0, 0);
        streamRow.addView(external, externalParams);
    }

    private void changeStream(int delta) {
        if (currentStream == null || currentStream.options.isEmpty()) {
            setOverlayVisible(true);
            return;
        }
        int nextIndex = Math.max(0, Math.min(selectedStreamIndex + delta, currentStream.options.size() - 1));
        if (nextIndex != selectedStreamIndex) {
            setOverlayVisible(true);
            loadStream(nextIndex, true);
        }
    }

    private void changeZoom(int delta) {
        zoomModeIndex = (zoomModeIndex + delta + ZOOM_MODES.length) % ZOOM_MODES.length;
        playerView.setResizeMode(ZOOM_MODES[zoomModeIndex]);
        zoomButton.setText("Khung hình: " + ZOOM_LABELS[zoomModeIndex]);
        setOverlayVisible(true);
    }

    private void openExternal() {
        String target = lastPlaybackUrl != null ? lastPlaybackUrl : pageUrl;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Không tìm thấy ứng dụng phát ngoài.", Toast.LENGTH_LONG).show();
        }
    }
}
