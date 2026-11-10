# SQLiteOpenHelper maps columns explicitly; models do not require reflection.
# Preserve names of surviving models/members, while allowing dead ones to shrink.
# Conditional rules stay inactive until the corresponding module exists.
-if class com.tierruos.stickers.data.model.**
-keep,allowoptimization,allowshrinking class com.tierruos.stickers.data.model.<1> {
    *;
}

# Keep only the external entry point and its no-argument framework constructor.
# Android's default rules preserve ContentProvider callback overrides.
-if class com.tierruos.stickers.provider.StickerContentProvider
-keep,allowoptimization class com.tierruos.stickers.provider.StickerContentProvider {
    public <init>();
}

# Retain useful crash locations; upload/archive mapping.txt for each release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
