package com.gathin.tvbridge;

/** One thing the user stopped watching: which app, what, and where. Plain Java so History can be unit tested. */
final class WatchItem {
    String pkg;
    /** The app's own id for the item (SmartTube: the YouTube video id; Jellyfin: the item id), when it shares one. */
    String mediaId;
    String title;
    String subtitle;
    /** Artwork the app pointed at, fetched once into the art folder. */
    String artUrl;
    long durationMs;
    long positionMs;
    /** Wall-clock time of the snapshot, or the server's last-played time. */
    long updatedAt;
    /** Came from a server list (Jellyfin): replaced wholesale on each sync, never expired, may start at 0. */
    boolean fromServer;
    /** A ready-to-start intent (URI form) when the generic launch isn't enough to reopen the item. */
    String intentUri;

    /** Same video in the same app means the same entry; apps that share neither id nor title keep one entry. */
    String key() {
        if (mediaId != null && !mediaId.isEmpty()) return pkg + "|id:" + mediaId;
        if (title != null && !title.isEmpty()) return pkg + "|t:" + title;
        return pkg + "|app";
    }
}
