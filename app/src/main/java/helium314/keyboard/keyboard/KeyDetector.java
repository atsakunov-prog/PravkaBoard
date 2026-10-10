/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * This class handles key detection.
 */
public class KeyDetector {
    /** Allows choosing a different key than the one whose hitbox contains the touch point. */
    public interface HitKeyCorrector {
        /**
         * @param hitKey the key whose hitbox contains the touch point
         * @param x the x-coordinate of the touch point in keyboard coordinates
         * @param y the y-coordinate of the touch point in keyboard coordinates
         * @return the key that should be considered as hit, usually {@code hitKey}
         */
        @NonNull Key correct(@NonNull Keyboard keyboard, @NonNull Key hitKey, int x, int y);
    }

    private final int mKeyHysteresisDistanceSquared;
    private final int mKeyHysteresisDistanceForSlidingModifierSquared;

    private Keyboard mKeyboard;
    private int mCorrectionX;
    private int mCorrectionY;
    @Nullable private HitKeyCorrector mHitKeyCorrector;

    public KeyDetector() {
        this(0.0f /* keyHysteresisDistance */, 0.0f /* keyHysteresisDistanceForSlidingModifier */);
    }

    /**
     * Key detection object constructor with key hysteresis distances.
     *
     * @param keyHysteresisDistance if the pointer movement distance is smaller than this, the
     * movement will not be handled as meaningful movement. The unit is pixel.
     * @param keyHysteresisDistanceForSlidingModifier the same parameter for sliding input that
     * starts from a modifier key such as shift and symbols key.
     */
    public KeyDetector(final float keyHysteresisDistance,
            final float keyHysteresisDistanceForSlidingModifier) {
        mKeyHysteresisDistanceSquared = (int)(keyHysteresisDistance * keyHysteresisDistance);
        mKeyHysteresisDistanceForSlidingModifierSquared = (int)(
                keyHysteresisDistanceForSlidingModifier * keyHysteresisDistanceForSlidingModifier);
    }

    public void setKeyboard(final Keyboard keyboard, final float correctionX,
            final float correctionY) {
        if (keyboard == null) {
            throw new NullPointerException();
        }
        mCorrectionX = (int)correctionX;
        mCorrectionY = (int)correctionY;
        mKeyboard = keyboard;
    }

    public int getKeyHysteresisDistanceSquared(final boolean isSlidingFromModifier) {
        return isSlidingFromModifier
                ? mKeyHysteresisDistanceForSlidingModifierSquared : mKeyHysteresisDistanceSquared;
    }

    public int getTouchX(final int x) {
        return x + mCorrectionX;
    }

    // TODO: Remove vertical correction.
    public int getTouchY(final int y) {
        return y + mCorrectionY;
    }

    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    public boolean alwaysAllowsKeySelectionByDraggingFinger() {
        return false;
    }

    public void setHitKeyCorrector(@Nullable final HitKeyCorrector corrector) {
        mHitKeyCorrector = corrector;
    }

    /**
     * Detects the key again at the position where the letter {@code key} was detected, because the
     * result of the {@link HitKeyCorrector} may have changed in the meantime, e.g. when another key
     * was released while this one was held. Non-letter keys are never exchanged.
     */
    public Key redetectLetterKey(final Key key, final int x, final int y) {
        if (mHitKeyCorrector == null || key == null || !Character.isLetter(key.getCode())) {
            return key;
        }
        final Key newKey = detectHitKey(x, y);
        return newKey != null && Character.isLetter(newKey.getCode()) ? newKey : key;
    }

    /**
     * Detect the key whose hitbox the touch point is in.
     *
     * @param x The x-coordinate of a touch point
     * @param y The y-coordinate of a touch point
     * @return the key that the touch point hits.
     */
    public Key detectHitKey(final int x, final int y) {
        if (mKeyboard == null) {
            return null;
        }
        final int touchX = getTouchX(x);
        final int touchY = getTouchY(y);

        int minDistance = Integer.MAX_VALUE;
        Key primaryKey = null;
        for (final Key key: mKeyboard.getNearestKeys(touchX, touchY)) {
            // An edge key always has its enlarged hitbox to respond to an event that occurred in
            // the empty area around the key. (@see Key#markAsLeftEdge(KeyboardParams)} etc.)
            if (!key.isOnKey(touchX, touchY)) {
                continue;
            }
            final int distance = key.squaredDistanceToEdge(touchX, touchY);
            if (distance > minDistance) {
                continue;
            }
            // To take care of hitbox overlaps, we compare key's code here too.
            if (primaryKey == null || distance < minDistance
                    || key.getCode() > primaryKey.getCode()) {
                minDistance = distance;
                primaryKey = key;
            }
        }
        if (primaryKey != null && mHitKeyCorrector != null) {
            return mHitKeyCorrector.correct(mKeyboard, primaryKey, touchX, touchY);
        }
        return primaryKey;
    }
}
