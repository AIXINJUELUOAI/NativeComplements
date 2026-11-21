package dev.local.sunwindowdemo.features.alive;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Rect;

import java.io.IOException;

public final class AlivePhoto {
    private static final int MAX_EDGE = 4096;
    private static final long MAX_PIXELS = 8_000_000;

    private AlivePhoto() {}

    static Bitmap decodeImport(ImageDecoder.Source source) throws IOException {
        return decodeFit(source, MAX_EDGE);
    }

    static Bitmap decodeFit(ImageDecoder.Source source, int maxEdge) throws IOException {
        return ImageDecoder.decodeBitmap(source, (decoder, info, input) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            if (width < 1 || height < 1 || width > 32768 || height > 32768
                    || (long) width * height > 100_000_000) {
                throw new IllegalArgumentException("照片尺寸不受支持");
            }
            double scale = Math.min(1d, Math.min((double) maxEdge / Math.max(width, height),
                    Math.sqrt((double) MAX_PIXELS / ((long) width * height))));
            decoder.setTargetSize(scaled(width, scale), scaled(height, scale));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    public static Bitmap decodeCrop(ImageDecoder.Source source, AliveCrop crop, int width, int height, int textureLimit)
            throws IOException {
        return ImageDecoder.decodeBitmap(source, (decoder, info, input) -> {
            int sourceWidth = info.getSize().getWidth(), sourceHeight = info.getSize().getHeight();
            AliveCrop.Window window = crop.window((float) sourceWidth / sourceHeight, (float) width / height);
            double cropWidth = sourceWidth * window.width(), cropHeight = sourceHeight * window.height();
            double scale = Math.min(1d, Math.min(Math.max(width / cropWidth, height / cropHeight),
                    (double) Math.min(textureLimit, MAX_EDGE) / Math.max(cropWidth, cropHeight)));
            int decodedWidth = scaled(sourceWidth, scale), decodedHeight = scaled(sourceHeight, scale);
            int left = Math.min(decodedWidth - 1, Math.round(window.left() * decodedWidth));
            int top = Math.min(decodedHeight - 1, Math.round(window.top() * decodedHeight));
            int right = Math.max(left + 1, Math.min(decodedWidth,
                    Math.round((window.left() + window.width()) * decodedWidth)));
            int bottom = Math.max(top + 1, Math.min(decodedHeight,
                    Math.round((window.top() + window.height()) * decodedHeight)));
            int maxEdge = Math.min(textureLimit, MAX_EDGE);
            decoder.setTargetSize(decodedWidth, decodedHeight);
            decoder.setCrop(new Rect(left, top, Math.min(right, left + maxEdge), Math.min(bottom, top + maxEdge)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private static int scaled(int size, double scale) {
        return Math.max(1, (int) Math.round(size * scale));
    }
}
