"""On-device yt-dlp bridge. No application backend or third-party extraction API."""
import json
import os
import re
import threading
import time
import traceback
import tempfile
import yt_dlp

_lock = threading.Lock()
_jobs = {}

# Directory containing executables named "ffmpeg" and "ffprobe". Set by
# prepare_ffmpeg(); None means yt-dlp falls back to searching PATH.
_FFMPEG_DIR = None


def prepare_ffmpeg(native_lib_dir, work_dir):
    """Expose the bundled ffmpeg/ffprobe to yt-dlp.

    The binaries are shipped in the APK as libffmpeg.so / libffprobe.so and
    Android extracts them into the app's native library directory, which is the
    only location an app may execute its own binaries on recent Android. yt-dlp
    looks for files literally named "ffmpeg" and "ffprobe", so we create those
    names as symlinks (falling back to copies) inside a writable directory.
    """
    global _FFMPEG_DIR
    try:
        os.makedirs(work_dir, exist_ok=True)
        found = False
        for name in ('ffmpeg', 'ffprobe'):
            src = os.path.join(native_lib_dir, 'lib' + name + '.so')
            dst = os.path.join(work_dir, name)
            if not os.path.exists(src):
                continue
            found = True
            try:
                if os.path.islink(dst) or os.path.exists(dst):
                    os.remove(dst)
            except Exception:
                pass
            try:
                os.symlink(src, dst)
            except Exception:
                import shutil
                shutil.copy2(src, dst)
                os.chmod(dst, 0o755)
        _FFMPEG_DIR = work_dir if found else None
        return _FFMPEG_DIR
    except Exception:
        _FFMPEG_DIR = None
        return None


def _ffmpeg_opts():
    return {'ffmpeg_location': _FFMPEG_DIR} if _FFMPEG_DIR else {}


def _safe_title(value):
    value = re.sub(r'[\\/:*?"<>|\x00-\x1f]', '_', value or 'video')
    value = re.sub(r'\s+', ' ', value).strip(' ._')
    return (value or 'video')[:120]


def _progress_file(job_id):
    return os.path.join(tempfile.gettempdir(), 'instdl_progress_' + job_id + '.json')


def _write_progress(job_id, payload):
    try:
        path = _progress_file(job_id)
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(payload, f)
    except Exception:
        pass


def _progress_hook(job_id):
    def hook(data):
        status = data.get('status', 'downloading')
        total = data.get('total_bytes') or data.get('total_bytes_estimate') or 0
        downloaded = data.get('downloaded_bytes') or 0
        percent = (downloaded / total * 100.0) if total else 0.0
        _write_progress(job_id, {
            'status': status,
            'percent': round(percent, 1),
            'downloaded_bytes': downloaded,
            'total_bytes': total,
            'speed': data.get('speed') or 0,
            'eta': data.get('eta'),
            'filename': data.get('filename') or '',
            'message': 'Downloading' if status == 'downloading' else 'Processing'
        })
    return hook


def extract_info(url):
    url = (url or '').strip()
    if not url.startswith(('https://', 'http://')):
        return json.dumps({'ok': False, 'error': 'Enter a valid http or https URL.'})
    opts = {
        'quiet': True, 'no_warnings': True, 'skip_download': True,
        'noplaylist': True, 'ignoreerrors': False,
        'socket_timeout': 20, 'retries': 2,
    }
    opts.update(_ffmpeg_opts())
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        if not info:
            return json.dumps({'ok': False, 'error': 'No video metadata was returned by yt-dlp.'})
        formats = []
        for f in (info.get('formats') or []):
            vcodec = f.get('vcodec') or 'none'
            acodec = f.get('acodec') or 'none'
            has_video = vcodec != 'none'
            has_audio = acodec != 'none'
            if not has_video and not has_audio:
                continue
            height = f.get('height')
            ext = f.get('ext') or 'unknown'
            size = f.get('filesize') or f.get('filesize_approx')
            resolution = f.get('resolution') or ((str(height) + 'p') if height else ('audio only' if not has_video else 'unknown'))
            formats.append({
                'format_id': str(f.get('format_id') or ''), 'ext': ext,
                'resolution': resolution, 'height': height or 0,
                'filesize': size or 0, 'vcodec': vcodec, 'acodec': acodec,
                'has_video': has_video, 'has_audio': has_audio,
                'note': f.get('format_note') or '', 'fps': f.get('fps') or 0,
                'format': f.get('format') or ''
            })
        # Sort formats so the UI can show useful qualities first.
        formats.sort(key=lambda x: (x['has_video'], x['height'], x['has_audio'], x['filesize']), reverse=True)
        return json.dumps({'ok': True, 'title': info.get('title') or 'Untitled video',
            'description': info.get('description') or '', 'duration': info.get('duration') or 0,
            'thumbnail': info.get('thumbnail') or '', 'extractor': info.get('extractor_key') or info.get('extractor') or 'yt-dlp',
            'webpage_url': info.get('webpage_url') or url, 'id': info.get('id') or '',
            'formats': formats})
    except Exception as e:
        message = str(e).strip() or 'Could not read video metadata.'
        return json.dumps({'ok': False, 'error': message[:500], 'trace': traceback.format_exc()[-1200:]})


def start_download(url, format_id, output_dir, job_id, mode='video_audio'):
    """Download to app-private external files. UI polls get_progress(job_id)."""
    os.makedirs(output_dir, exist_ok=True)
    output_template = os.path.join(output_dir, '%(extractor)s_%(title).120B_%(id)s.%(ext)s')
    if mode == 'audio':
        selector = format_id or 'bestaudio/best'
    elif mode == 'video':
        selector = format_id or 'bestvideo/best'
    else:
        selector = format_id or 'best[acodec!=none][vcodec!=none]/best'
    opts = {
        'quiet': True, 'no_warnings': True, 'noplaylist': True,
        'format': selector, 'outtmpl': output_template,
        'progress_hooks': [_progress_hook(job_id)], 'retries': 3,
        'fragment_retries': 3, 'continuedl': True, 'overwrites': False,
        'socket_timeout': 20, 'windowsfilenames': True,
        # Merge separate video/audio streams into one file. Many sites
        # (including YouTube) only offer DASH streams, so this is required.
        'merge_output_format': 'mp4',
        'format_sort': ['res', 'ext:mp4:m4a'],
    }
    opts.update(_ffmpeg_opts())
    _write_progress(job_id, {'status': 'starting', 'percent': 0, 'message': 'Starting download'})
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            code = ydl.download([url])
        if code == 0:
            _write_progress(job_id, {'status': 'finished', 'percent': 100, 'message': 'Download complete'})
            return json.dumps({'ok': True})
        _write_progress(job_id, {'status': 'error', 'message': 'yt-dlp returned an error'})
        return json.dumps({'ok': False, 'error': 'yt-dlp could not complete the download.'})
    except Exception as e:
        _write_progress(job_id, {'status': 'error', 'message': str(e)[:500]})
        return json.dumps({'ok': False, 'error': str(e)[:500]})


def get_progress(job_id):
    try:
        with open(_progress_file(job_id), 'r', encoding='utf-8') as f:
            return f.read()
    except Exception:
        return json.dumps({'status': 'starting', 'percent': 0, 'message': 'Preparing download'})


def cleanup_progress(job_id):
    try:
        os.remove(_progress_file(job_id))
    except Exception:
        pass
    return True


def version():
    return yt_dlp.version.__version__
