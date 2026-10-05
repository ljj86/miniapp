"""SIM-HMAC-1 byte protocol helper. SIMULATION ONLY, not a payment SDK.
Replay and provider_event_id uniqueness must be committed in a durable inbox by the server.
This module verifies bytes/timestamp only; it does NOT replace persistence or authorization.
"""
import base64, hashlib, hmac, re, time

def canonical(timestamp: str, nonce: str, raw_body: bytes) -> bytes:
    if not re.fullmatch(r'[0-9]{10,13}',timestamp): raise ValueError('invalid timestamp')
    if not re.fullmatch(r'[A-Za-z0-9_-]{16,128}',nonce): raise ValueError('invalid nonce')
    raw_body.decode('utf-8')  # Reject malformed encoding; never parse/re-serialize before verification.
    return timestamp.encode('ascii')+b'\n'+nonce.encode('ascii')+b'\n'+raw_body+b'\n'

def sign(secret: bytes, timestamp: str, nonce: str, raw_body: bytes) -> str:
    if len(secret)<32: raise ValueError('test/production-managed key must contain >=32 bytes')
    return base64.b64encode(hmac.new(secret,canonical(timestamp,nonce,raw_body),hashlib.sha256).digest()).decode('ascii')

def verify(secret: bytes, timestamp: str, nonce: str, raw_body: bytes, signature: str, *, now_seconds: int | None=None) -> bool:
    try:
        now=int(time.time()) if now_seconds is None else now_seconds
        # timestamp is Unix SECONDS even if up to 13 characters accepted syntactically.
        if abs(now-int(timestamp))>300:return False
        received=base64.b64decode(signature,validate=True)
        expected=base64.b64decode(sign(secret,timestamp,nonce,raw_body))
        return len(received)==32 and hmac.compare_digest(received,expected)
    except (ValueError,TypeError,UnicodeError):return False
