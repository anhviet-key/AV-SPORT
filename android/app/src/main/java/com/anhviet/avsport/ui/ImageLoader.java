package com.anhviet.avsport.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageLoader {
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Map<String, Bitmap> cache = new ConcurrentHashMap<>();

    public void load(ImageView target, String url) {
        if (target == null || url == null || url.length() == 0) {
            return;
        }

        Bitmap cached = cache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        target.setTag(url);
        executor.execute(() -> {
            Bitmap bitmap = fetch(url);
            if (bitmap == null) {
                return;
            }
            cache.put(url, bitmap);
            target.post(() -> {
                Object tag = target.getTag();
                if (url.equals(tag)) {
                    target.setImageBitmap(bitmap);
                }
            });
        });
    }

    private Bitmap fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "AV Sport Android TV");
            try (InputStream stream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(stream);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
