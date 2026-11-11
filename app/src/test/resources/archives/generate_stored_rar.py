# Generated fixture writer: stored entries only, no RAR compression code.
import struct,zlib,pathlib,urllib.request,binascii
root=pathlib.Path(__file__).resolve().parent
def vint(n):
 b=bytearray()
 while n>=128:b.append((n&127)|128);n>>=7
 b.append(n);return bytes(b)
def block5(data):
 b=vint(len(data))+data
 return struct.pack('<I',zlib.crc32(b))+b
out=b'Rar!\x1a\x07\x01\x00'+block5(b'\x01\x00\x00')
for name,text in [('Папка/Книга.fb2','<FictionBook>first</FictionBook>'),('Другая/Книга.fb2','<FictionBook>second</FictionBook>')]:
 data=text.encode();n=name.encode()
 header=vint(2)+vint(2)+vint(len(data))+vint(4)+vint(len(data))+vint(0o100644)+struct.pack('<I',zlib.crc32(data))+vint(0)+vint(1)+vint(len(n))+n
 out+=block5(header)+data
out+=block5(b'\x05\x00\x00');(root/'rar5-books.rar').write_bytes(out)
def block4(kind,flags,payload):
 b=struct.pack('<BHH',kind,flags,7+len(payload))+payload
 return struct.pack('<H',zlib.crc32(b)&65535)+b
name=b'one.fb2';data=b'<FictionBook>one</FictionBook>'
payload=struct.pack('<IIBIIBBHI',len(data),len(data),3,zlib.crc32(data),0,20,0x30,len(name),0o100644)+name
out=b'Rar!\x1a\x07\x00'+block4(0x73,0,bytes(6))+block4(0x74,0x8000,payload)+data+block4(0x7b,0,b'')
(root/'rar4-books.rar').write_bytes(out)
