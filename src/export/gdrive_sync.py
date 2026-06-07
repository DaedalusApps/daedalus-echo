"""Google Drive upload using Drive API v3 with OAuth2."""

import os
from pathlib import Path

from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

SCOPES = ["https://www.googleapis.com/auth/drive.file"]


def _get_credentials(credentials_file: str, token_file: str) -> Credentials:
    creds: Credentials | None = None

    if os.path.exists(token_file):
        creds = Credentials.from_authorized_user_file(token_file, SCOPES)

    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
    elif not creds or not creds.valid:
        flow = InstalledAppFlow.from_client_secrets_file(credentials_file, SCOPES)
        creds = flow.run_local_server(port=0)

    Path(token_file).write_text(creds.to_json(), encoding="utf-8")
    return creds


def _find_or_create_folder(service, name: str) -> str:
    # Escape single quotes in the name to avoid breaking the Drive API query.
    safe_name = name.replace("\\", "\\\\").replace("'", "\\'")
    query = (
        f"mimeType='application/vnd.google-apps.folder' "
        f"and name='{safe_name}' and trashed=false"
    )
    results = service.files().list(q=query, fields="files(id)").execute()
    files = results.get("files", [])

    if files:
        return files[0]["id"]

    metadata = {
        "name": name,
        "mimeType": "application/vnd.google-apps.folder",
    }
    folder = service.files().create(body=metadata, fields="id").execute()
    return folder["id"]


def upload_file(
    local_path: Path,
    folder_name: str = "Notetaker",
    *,
    credentials_file: str | None = None,
    token_file: str | None = None,
) -> str:
    credentials_file = credentials_file or os.environ.get("GDRIVE_CREDENTIALS_FILE")
    if not credentials_file:
        raise EnvironmentError(
            "GDRIVE_CREDENTIALS_FILE is not set. "
            "Download your OAuth2 credentials JSON from Google Cloud Console "
            "and set the env var to its path (or add it to your .env file)."
        )
    token_file = token_file or os.environ.get(
        "GDRIVE_TOKEN_FILE", str(Path(credentials_file).parent / "token.json")
    )

    creds = _get_credentials(credentials_file, token_file)
    service = build("drive", "v3", credentials=creds)

    folder_id = _find_or_create_folder(service, folder_name)

    file_metadata = {"name": local_path.name, "parents": [folder_id]}
    media = MediaFileUpload(str(local_path), resumable=True)
    uploaded = (
        service.files()
        .create(body=file_metadata, media_body=media, fields="id, webViewLink")
        .execute()
    )

    return uploaded.get("webViewLink") or uploaded["id"]
