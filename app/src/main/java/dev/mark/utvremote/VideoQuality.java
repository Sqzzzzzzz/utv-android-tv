package dev.mark.utvremote;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import java.util.SortedSet;
import java.util.TreeSet;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class VideoQuality {
    static String label(int height) { return height == 0 ? "Авто" : height + "p"; }
    static SortedSet<Integer> available(Tracks tracks) {
        SortedSet<Integer> heights = new TreeSet<>(); heights.add(0);
        for (Tracks.Group group : tracks.getGroups()) if (group.getType() == C.TRACK_TYPE_VIDEO)
            for (int i = 0; i < group.length; i++) if (group.isTrackSupported(i) && group.getTrackFormat(i).height > 0)
                heights.add(group.getTrackFormat(i).height);
        return heights;
    }
    static int effective(Tracks tracks, int requested) {
        if (requested == 0) return 0;
        SortedSet<Integer> heights = available(tracks); heights.remove(0);
        if (heights.isEmpty()) return requested;
        int best = heights.first(); for (int height : heights) if (height <= requested) best = height;
        return best;
    }
    static TrackSelectionParameters parameters(TrackSelectionParameters current, Tracks tracks, int requested) {
        TrackSelectionParameters.Builder builder = current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMinVideoSize(0, 0).setMaxVideoSize(Integer.MAX_VALUE, requested > 0 ? requested : Integer.MAX_VALUE);
        if (requested > 0) {
            Tracks.Group best = null; int bestIndex = -1, bestHeight = 0;
            for (Tracks.Group group : tracks.getGroups()) if (group.getType() == C.TRACK_TYPE_VIDEO)
                for (int i = 0; i < group.length; i++) {
                    int height = group.getTrackFormat(i).height;
                    if (!group.isTrackSupported(i) || height <= 0) continue;
                    if (best == null || (height <= requested && (bestHeight > requested || height > bestHeight))
                            || (height > requested && bestHeight > requested && height < bestHeight)) {
                        best = group; bestIndex = i; bestHeight = height;
                    }
                }
            if (best != null) builder.addOverride(new TrackSelectionOverride(best.getMediaTrackGroup(), bestIndex));
        }
        return builder.build();
    }
    static void apply(Player player, int requested) {
        TrackSelectionParameters next = parameters(player.getTrackSelectionParameters(), player.getCurrentTracks(), requested);
        if (!next.equals(player.getTrackSelectionParameters())) player.setTrackSelectionParameters(next);
    }
}
