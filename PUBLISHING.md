# Publish to GitHub

The plugin lives in this folder: `/Users/opada/StudioProjects/webex_calling`

## 1. Create an empty repo on GitHub

- Name: `webex_calling` (or any name — update URLs below if different)
- Do **not** add a README (this repo already has one)

## 2. Push this repository

Replace `YOUR_GITHUB_USERNAME` with your GitHub username:

```bash
cd /Users/opada/StudioProjects/webex_calling

# Update homepage/repository in pubspec.yaml (replace YOUR_GITHUB_USERNAME)

git remote add origin https://github.com/YOUR_GITHUB_USERNAME/webex_calling.git
git push -u origin main
```

## 3. Use the plugin in your Flutter app

In the app `pubspec.yaml`:

```yaml
dependencies:
  webex_calling:
    git:
      url: https://github.com/YOUR_GITHUB_USERNAME/webex_calling.git
      ref: main
```

Then:

```bash
cd /Users/opada/StudioProjects/untitled1
flutter pub get
cd ios && pod install && cd ..
```

The app `android/settings.gradle.kts` already resolves the plugin path from `.flutter-plugins-dependencies` (works with Git dependencies in the pub cache).

## Private repository

Use SSH or a personal access token:

```yaml
webex_calling:
  git:
    url: git@github.com:YOUR_GITHUB_USERNAME/webex_calling.git
    ref: main
```

Or HTTPS with a token when prompted during `flutter pub get`.
