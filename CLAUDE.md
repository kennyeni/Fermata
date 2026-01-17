# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APKs (all flavors)
./gradlew assembleDebug

# Build specific flavor
./gradlew assembleMobileDebug
./gradlew assembleAutoDebug

# Build release AAB (requires APP_ID_SFX)
./gradlew bundleAutoRelease -PAPP_ID_SFX=.your.suffix.here

# Build for specific ABI
./gradlew assembleDebug -PABI=arm64-v8a

# Clean build
./gradlew clean

# Run tests
./gradlew test
```

**Environment Variables:**
- `ANDROID_SDK_ROOT` - Path to Android SDK
- `NO_GS=true` - Disable Google Services (excludes gdrive module)

## Architecture Overview

Fermata is an Android media player built on **Android Dynamic Feature Modules** with a custom **addon plugin system**.

### Module Structure

```
/fermata/           - Main application module (MediaBrowserService-based player)
/modules/           - Dynamic feature modules (installed on-demand via Play Feature Delivery)
  ├── cast         - Chromecast support (CastMediaEngineProvider)
  ├── exoplayer    - ExoPlayer media engine
  ├── vlc          - VLC media engine (LibVLC 3.6.5)
  ├── web          - YouTube + Web browser addons
  ├── gdrive       - Google Drive VFS provider
  ├── sftp         - SFTP VFS provider (JSch)
  ├── smb          - SMB/CIFS VFS provider (smbj)
  ├── whisper      - Speech-to-text (native C++ Whisper via CMake/JNI)
  ├── mlkit        - ML Kit translation
  └── ...
/depends/utils/     - Shared utility library (VFS abstractions, async, UI)
```

### Addon System

Addons are declared in each module's `build.gradle` under `ext.addons`:
```groovy
ext.addons = [
    [name: 'youtube', icon: 'youtube', class: 'me.aap.fermata.addon.web.yt.YoutubeAddon', ...]
]
```

The root `build.gradle` collects all addon metadata and generates `BuildConfig.ADDONS[]` at compile time.

**Key Addon Interfaces** (`fermata/src/main/java/me/aap/fermata/addon/`):
- `FermataAddon` - Base interface with lifecycle (`install()`/`uninstall()`)
- `FermataMediaServiceAddon` - Hooks into media service (`onServiceCreate(MediaSessionCallback)`)
- `FermataToolAddon` - Contributes toolbar UI
- `FermataFragmentAddon` - Provides UI fragments
- `MediaLibAddon` - Content source extensions

**AddonManager** (`AddonManager.java`) handles:
- Reflection-based class loading via `Class.forName()`
- On-demand module installation via Play Feature Delivery
- Dependency resolution between addons
- Preference-driven enable/disable

### Virtual File System (VFS)

**FermataVfsManager** (`fermata/src/main/java/me/aap/fermata/vfs/FermataVfsManager.java`) manages multiple file system implementations:
- Built-in: `LocalFileSystem`, `GenericFileSystem`, `ContentFileSystem`, `M3uFileSystem`
- Dynamic: `GdriveFileSystem`, `SftpFileSystem`, `SmbFileSystem` (loaded from modules)

Each module provides a `VfsProviderBase` subclass with:
- `createFileSystem()` - Factory method
- `addFolder()`/`removeFolder()` - Root management
- Preference-driven configuration

### Product Flavors

- **mobile** - Standard Android phones/tablets (`BuildConfig.AUTO = false`)
- **auto** - Android Automotive OS (`BuildConfig.AUTO = true`) with mirroring support

### Key Entry Points

- `FermataApplication` - App initialization, manages `AddonManager` and `FermataVfsManager`
- `MainActivity` - Main UI with media browser and playback controls
- `FermataMediaService` - `MediaBrowserService` implementation with addon hooks

### Native Code

The `whisper` module uses CMake for native C++ compilation:
- JNI bindings to OpenAI Whisper library
- Configured for Release builds only
- NDK version: 29.0.14206865

### CI/CD

GitHub Actions workflow (`.github/workflows/build.yml`) runs Docker-based builds with Gradle caching on pull requests.
