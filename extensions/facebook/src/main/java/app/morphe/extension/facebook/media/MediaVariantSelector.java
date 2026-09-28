/*
* Copyright 2026 De-Vanced
* [https://github.com/RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced)
*/

package app.morphe.extension.facebook.media;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MediaVariantSelector {
    private MediaVariantSelector() {
    }

    public static MediaVariant bestDashVideo(List<MediaVariant> variants) {
        return bestOfKind(variants, MediaVariant.Kind.DASH_VIDEO);
    }

    public static MediaVariant bestDashVideoForTarget(
            List<MediaVariant> variants,
            int targetQualityEdge
    ) {
        return bestForTarget(
                variants,
                MediaVariant.Kind.DASH_VIDEO,
                targetQualityEdge
        );
    }

    public static MediaVariant bestDashAudio(List<MediaVariant> variants) {
        return bestOfKind(variants, MediaVariant.Kind.DASH_AUDIO);
    }

    public static MediaVariant bestImage(List<MediaVariant> variants) {
        return bestOfKind(variants, MediaVariant.Kind.IMAGE);
    }

    public static MediaVariant bestProgressiveVideo(
            List<MediaVariant> variants
    ) {
        return bestOfKind(variants, MediaVariant.Kind.PROGRESSIVE_VIDEO);
    }

    public static DashPair bestDashPair(List<MediaVariant> variants) {
        return bestDashPair(variants, 0);
    }

    public static DashPair bestDashPair(
            List<MediaVariant> variants,
            int targetQualityEdge
    ) {
        if (variants == null || variants.isEmpty()) return null;
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        for (MediaVariant variant : variants) {
            if (variant == null ||
                    (variant.kind != MediaVariant.Kind.DASH_VIDEO &&
                            variant.kind != MediaVariant.Kind.DASH_AUDIO)) {
                continue;
            }
            String groupId = variant.manifestId.isEmpty()
                    ? "default"
                    : variant.manifestId;
            Group group = groups.get(groupId);
            if (group == null) {
                group = new Group();
                groups.put(groupId, group);
            }
            if (variant.kind == MediaVariant.Kind.DASH_VIDEO &&
                    variant.url != null &&
                    !variant.url.isEmpty()) {
                group.videos.add(variant);
            } else if (variant.kind == MediaVariant.Kind.DASH_AUDIO &&
                    (group.audio == null ||
                            compareQuality(variant, group.audio) > 0)) {
                group.audio = variant;
            }
        }

        DashPair best = null;
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            Group group = entry.getValue();
            MediaVariant video = bestForTarget(
                    group.videos,
                    MediaVariant.Kind.DASH_VIDEO,
                    targetQualityEdge
            );
            if (video == null || group.audio == null) continue;
            DashPair candidate = new DashPair(
                    video,
                    group.audio
            );
            if (best == null ||
                    compareForTarget(
                            candidate.video,
                            best.video,
                            targetQualityEdge
                    ) > 0 ||
                    (compareForTarget(
                            candidate.video,
                            best.video,
                            targetQualityEdge
                    ) == 0 &&
                            compareQuality(
                                    candidate.audio,
                                    best.audio
                            ) > 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private static MediaVariant bestForTarget(
            List<MediaVariant> variants,
            MediaVariant.Kind kind,
            int targetQualityEdge
    ) {
        if (variants == null || variants.isEmpty()) return null;
        MediaVariant best = null;
        for (MediaVariant variant : variants) {
            if (variant == null ||
                    variant.kind != kind ||
                    variant.url == null ||
                    variant.url.isEmpty()) {
                continue;
            }
            if (best == null ||
                    compareForTarget(
                            variant,
                            best,
                            targetQualityEdge
                    ) > 0) {
                best = variant;
            }
        }
        return best;
    }

    static int compareForTarget(
            MediaVariant left,
            MediaVariant right,
            int targetQualityEdge
    ) {
        if (targetQualityEdge <= 0) return compareQuality(left, right);
        int leftEdge = left.qualityLabelEdge();
        int rightEdge = right.qualityLabelEdge();
        boolean leftExact = leftEdge == targetQualityEdge;
        boolean rightExact = rightEdge == targetQualityEdge;
        if (leftExact != rightExact) return leftExact ? 1 : -1;

        boolean leftLower = leftEdge < targetQualityEdge;
        boolean rightLower = rightEdge < targetQualityEdge;
        if (leftLower != rightLower) return leftLower ? 1 : -1;
        if (leftEdge != rightEdge) {
            return leftLower
                    ? Integer.compare(leftEdge, rightEdge)
                    : Integer.compare(rightEdge, leftEdge);
        }
        return compareQuality(left, right);
    }

    private static MediaVariant bestOfKind(
            List<MediaVariant> variants,
            MediaVariant.Kind kind
    ) {
        if (variants == null || variants.isEmpty()) return null;
        MediaVariant best = null;
        for (MediaVariant variant : variants) {
            if (variant == null ||
                    variant.kind != kind ||
                    variant.url == null ||
                    variant.url.isEmpty()) {
                continue;
            }
            if (best == null || compareQuality(variant, best) > 0) {
                best = variant;
            }
        }
        return best;
    }

    static int compareQuality(MediaVariant left, MediaVariant right) {
        int qualityEdge = Integer.compare(
                left.qualityLabelEdge(),
                right.qualityLabelEdge()
        );
        if (qualityEdge != 0) return qualityEdge;

        int pixels = Long.compare(left.pixelCount(), right.pixelCount());
        if (pixels != 0) return pixels;

        int bitrate = Long.compare(left.bitrate, right.bitrate);
        if (bitrate != 0) return bitrate;

        if (left.hasAudio != right.hasAudio) {
            return left.hasAudio ? 1 : -1;
        }
        return left.url.compareTo(right.url);
    }

    private static final class Group {
        final List<MediaVariant> videos = new ArrayList<>();
        MediaVariant audio;
    }

    public static final class DashPair {
        public final MediaVariant video;
        public final MediaVariant audio;

        DashPair(MediaVariant video, MediaVariant audio) {
            this.video = video;
            this.audio = audio;
        }
    }
}
