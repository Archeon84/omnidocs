# OmniDocs

A full-stack Android document and note-taking application with OCR, offline AI capabilities, and customizable themes.

## Features

- **Rich Text Editor** - Create and edit notes with formatting (bold, italic, lists, headings, etc.)
- **OCR Scanner** - Extract text from images using Google ML Kit
- **Offline AI** - Summarize, proofread, and rewrite notes using Gemini Nano
- **Multi-language** - Support for English and Bahasa Melayu
- **Themes** - 6+ customizable themes (Light, Dark, AMOLED, Sepia, Ocean, Forest, Lavender)
- **Cloud Sync** - Firebase integration for syncing notes across devices
- **Search** - Find notes quickly with full-text search
- **Pin Notes** - Keep important notes at the top

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **AI**: Gemini Nano (ML Kit GenAI)
- **OCR**: Google ML Kit (Text Recognition v2)
- **Database**: Room (local) + Firestore (cloud)
- **Auth**: Firebase Auth
- **DI**: Hilt

## Setup

1. Clone the repository
2. Open in Android Studio
3. Add your `google-services.json` file to the app directory
4. Build and run

## Project Structure

```
app/src/main/java/com/notes/app/
├── ai/                 # Gemini Nano AI service
├── data/               # Data layer (Room, Firestore)
├── di/                 # Hilt dependency injection
├── domain/             # Domain models
├── ocr/                # OCR services
└── ui/                 # UI layer (Compose screens, components)
```

## Configuration

### Firebase Setup

1. Create a Firebase project
2. Enable Authentication (Email/Password + Google)
3. Create Firestore database
4. Download `google-services.json` and place in app directory

### ML Kit Setup

The app uses the following ML Kit features:
- Text Recognition v2 (OCR)
- Digital Ink Recognition (Handwriting)
- Document Scanner
- GenAI (Gemini Nano)

## License

MIT License
