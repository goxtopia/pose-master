import PyInstaller.__main__
import os
import shutil

# Clean up previous builds
if os.path.exists('dist'):
    shutil.rmtree('dist')
if os.path.exists('build'):
    shutil.rmtree('build')

# Define data to include
# Format: "source_path;dest_path" for Windows
# The user specifically asked for a Windows packaging script.
# We enforce the semicolon separator which is required for Windows PyInstaller.
sep = ';' 

datas = [
    f'libs{sep}libs',
    f'index.html{sep}.',
    f'style.css{sep}.',
    f'app.js{sep}.',
]

print("Starting PyInstaller build for Windows...")
print(f"Including data: {datas}")

# Run PyInstaller
PyInstaller.__main__.run([
    'server_flask.py',
    '--name=PostureMonitor',
    '--onefile',
    # '--noconsole', # Commented out to keep console visible for server logs/errors
    '--clean',
] + [f'--add-data={d}' for d in datas])

print("Build complete. Check dist/ folder.")
