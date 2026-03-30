import PyInstaller.__main__
import shutil
import time
import traceback
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
STATUS_FILE = BASE_DIR / 'build_exe_status.txt'
ERROR_FILE = BASE_DIR / 'build_exe_error.log'

# Clean previous build
dist_dir = BASE_DIR / 'dist'
build_dir = BASE_DIR / 'build'

def remove_dir_with_retry(dir_path: Path, retries: int = 6, delay_sec: float = 1.0):
    if not dir_path.exists():
        return

    last_error = None
    for _ in range(retries):
        try:
            shutil.rmtree(dir_path)
            return
        except OSError as exc:
            last_error = exc
            time.sleep(delay_sec)

    if last_error is not None:
        raise last_error


def run_pyinstaller(dist_dir: Path, build_dir: Path):
    try:
        PyInstaller.__main__.run([
            str(BASE_DIR / 'run_server.py'),
            '--name=12306-backend',
            '--onedir',
            '--noconfirm',
            '--clean',
            '--log-level=WARN',
            f'--distpath={dist_dir}',
            f'--workpath={build_dir}',
            f'--specpath={BASE_DIR}',
            # Include the app package.
            # Since run_server imports main, and main imports app, PyInstaller should find it.
            # Hidden imports for Uvicorn
            '--hidden-import=uvicorn.logging',
            '--hidden-import=uvicorn.loops',
            '--hidden-import=uvicorn.loops.auto',
            '--hidden-import=uvicorn.protocols',
            '--hidden-import=uvicorn.protocols.http',
            '--hidden-import=uvicorn.protocols.http.auto',
            '--hidden-import=uvicorn.protocols.websockets',
            '--hidden-import=uvicorn.protocols.websockets.auto',
            '--hidden-import=uvicorn.lifespan',
            '--hidden-import=uvicorn.lifespan.on',
            '--hidden-import=aiosqlite',
            '--hidden-import=sqlalchemy.dialects.sqlite.aiosqlite',
            '--collect-submodules=aiosqlite',
        ])
    except SystemExit as exc:
        # PyInstaller may raise SystemExit even on success.
        if exc.code not in (0, None):
            raise RuntimeError(f'PyInstaller failed with exit code: {exc.code}') from exc


def copy_data_assets(dist_dir: Path):
    # Prefer backend/data/assets, fallback to project-root data/assets
    src_candidates = [
        BASE_DIR / 'data' / 'assets',
        BASE_DIR.parent / 'data' / 'assets',
    ]
    src_data_assets = next((p for p in src_candidates if p.exists()), src_candidates[0])
    dst_data_assets = dist_dir / '12306-backend' / 'data' / 'assets'
    dst_data_assets.mkdir(parents=True, exist_ok=True)

    if src_data_assets.exists():
        for item in src_data_assets.iterdir():
            if item.is_file():
                shutil.copy2(item, dst_data_assets / item.name)


def main():
    dist_dir = BASE_DIR / 'dist'
    build_dir = BASE_DIR / 'build'

    if STATUS_FILE.exists():
        STATUS_FILE.unlink()
    if ERROR_FILE.exists():
        ERROR_FILE.unlink()

    try:
        remove_dir_with_retry(dist_dir)
        remove_dir_with_retry(build_dir)

        run_pyinstaller(dist_dir, build_dir)
        copy_data_assets(dist_dir)

        STATUS_FILE.write_text('success\n', encoding='utf-8')
        print('Build complete. Data files copied.')
    except Exception:
        error_text = traceback.format_exc()
        ERROR_FILE.write_text(error_text, encoding='utf-8')
        STATUS_FILE.write_text('failed\n', encoding='utf-8')
        raise


if __name__ == '__main__':
    main()
