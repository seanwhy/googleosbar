# kotlinx.serialization keeps its generated serializers via its own consumer rules.
# Keep the accessibility service (referenced from the manifest only). The tile services and the other manifest
# components are kept by the rules AGP generates from the manifest.
-keep class com.meridian11.books.bentobar.bar.BarService { *; }
