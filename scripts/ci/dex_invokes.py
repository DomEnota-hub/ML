#!/usr/bin/env python3
"""Narrow static DEX invoke argument-word check; not an ART verifier.

DEX versions 035–039, ordinary invoke-kind and invoke-kind/range. The latter
must carry a receiver word for non-static methods. Follow instruction lengths
including data payloads instead of scanning raw bytes for opcode patterns.
Reference: https://source.android.com/docs/core/runtime/dalvik-bytecode
"""
import argparse
import re
import struct
import sys
import zipfile
from functools import lru_cache

WIDTH = [0] * 256
for lo, hi, width in [(0,1,1),(2,2,2),(3,3,3),(4,4,1),(5,5,2),(6,6,3),(7,7,1),(8,8,2),(9,9,3),(10,18,1),(19,19,2),(20,20,3),(21,22,2),(23,23,3),(24,24,5),(25,26,2),(27,27,3),(28,28,2),(29,30,1),(31,32,2),(33,33,1),(34,35,2),(36,38,3),(39,40,1),(41,41,2),(42,44,3),(45,61,2),(68,109,2),(110,114,3),(116,120,3),(123,143,1),(144,175,2),(176,207,1),(208,226,2),(250,251,4),(252,253,3),(254,255,2)]:
    WIDTH[lo:hi+1] = [width]*(hi-lo+1)

class DexError(ValueError): pass

class Dex:
    def __init__(self, data):
        self.data = data
        if len(data)<112 or not re.fullmatch(rb'dex\n03[5-9]\x00',data[:8]):
            raise DexError('Unsupported DEX header/version')
        if self.u32(32)!=len(data) or self.u32(36)!=112 or self.u32(40)!=0x12345678:
            raise DexError('Invalid DEX size/header/endian')
        self.strs,self.str_off,self.types,self.type_off,self.protos,self.proto_off,self.fields,self.field_off,self.methods,self.method_off,self.classes,self.class_off=struct.unpack_from('<12I',data,56)
        for n,off,width in [(self.strs,self.str_off,4),(self.types,self.type_off,4),(self.protos,self.proto_off,12),(self.methods,self.method_off,8),(self.classes,self.class_off,32)]:
            if n and (off<112 or off+n*width>len(data)):raise DexError('Invalid DEX table bounds')
    def u16(self,p):return struct.unpack_from('<H',self.data,p)[0]
    def u32(self,p):return struct.unpack_from('<I',self.data,p)[0]
    def leb(self,p):
        val=0
        for i in range(5):
            b=self.data[p];p+=1;val|=(b&127)<<(7*i)
            if not b&128:return val,p
        raise DexError('Invalid ULEB128')
    @lru_cache(maxsize=65536)
    def string(self,i):
        if not 0<=i<self.strs:raise DexError('String index out of range')
        _,p=self.leb(self.u32(self.str_off+4*i));end=self.data.index(0,p)
        return self.data[p:end].decode('utf-8','replace')
    @lru_cache(maxsize=65536)
    def typ(self,i):
        if not 0<=i<self.types:raise DexError('Type index out of range')
        return self.string(self.u32(self.type_off+4*i))
    @lru_cache(maxsize=65536)
    def method(self,i):
        if not 0<=i<self.methods:raise DexError('Method index out of range')
        cls,proto,name=struct.unpack_from('<HHI',self.data,self.method_off+8*i)
        return self.typ(cls),self.string(name),proto
    @lru_cache(maxsize=65536)
    def param_words(self,proto):
        if not 0<=proto<self.protos:raise DexError('Prototype index out of range')
        p=self.u32(self.proto_off+12*proto+8)
        if p==0:return 0
        n=self.u32(p)
        if p+4+2*n>len(self.data):raise DexError('Parameter list out of bounds')
        return sum(2 if self.typ(self.u16(p+4+2*i)) in ('J','D') else 1 for i in range(n))
    def code_methods(self,prefix=None):
        for i in range(self.classes):
            p=self.class_off+32*i;cls=self.typ(self.u32(p));p=self.u32(p+24)
            if p==0 or (prefix and not cls.startswith(prefix)):continue
            counts=[]
            for _ in range(4):
                n,p=self.leb(p);counts.append(n)
            for _ in range(counts[0]+counts[1]):
                _,p=self.leb(p);_,p=self.leb(p)
            for n in counts[2:]:
                index=0
                for _ in range(n):
                    d,p=self.leb(p);index+=d;_,p=self.leb(p);code,p=self.leb(p)
                    if code:yield cls,index,code
    def inspect(self,code):
        if code%4 or code+16>len(self.data):raise DexError('Invalid code_item offset')
        regs,ins,outs=struct.unpack_from('<3H',self.data,code)
        count=self.u32(code+12);start=code+16
        if start+count*2>len(self.data) or regs<ins:raise DexError('Invalid code_item bounds')
        words=memoryview(self.data)[start:start+2*count].cast('H')
        pc=0
        while pc<count:
            word=words[pc];op=word&255;width=WIDTH[op]
            if op==0 and word!=0:
                if pc%2 or pc+2>count:raise DexError('Invalid payload alignment/header')
                if word==0x0100:width=4+2*words[pc+1]
                elif word==0x0200:width=2+4*words[pc+1]
                elif word==0x0300 and pc+4<=count:
                    width=4+(words[pc+1]*(words[pc+2]|(words[pc+3]<<16))+1)//2
                else:raise DexError('Unknown/truncated payload')
            if not width or pc+width>count:raise DexError(f'Unknown/truncated opcode 0x{op:02x} at {pc:#x}')
            if 0x6e<=op<=0x72 or 0x74<=op<=0x78:
                ranged=op>=0x74
                actual=word>>8 if ranged else word>>12
                target=words[pc+1];cls,name,proto=self.method(target)
                expected=self.param_words(proto)+(0 if op in (0x71,0x77) else 1)
                if actual!=expected:
                    yield pc,f'opcode=0x{op:02x} count={actual} expected={expected} target={cls}->{name}'
                elif actual>outs:
                    yield pc,f'opcode=0x{op:02x} count={actual} exceeds outs_size={outs}'
                elif ranged and actual and words[pc+2]+actual>regs:
                    yield pc,f'opcode=0x{op:02x} argument registers exceed registers_size={regs}'
                elif not ranged:
                    argword=words[pc+2];argregs=[argword&15,(argword>>4)&15,(argword>>8)&15,(argword>>12)&15,(word>>8)&15]
                    if actual>5 or any(r>=regs for r in argregs[:actual]):yield pc,f'opcode=0x{op:02x} invalid argument registers'
            pc+=width
    def scan(self,prefix=None):
        count=0;errors=[]
        for cls,index,code in self.code_methods(prefix):
            count+=1
            name=self.method(index)[1]
            try:
                errors.extend(f'{cls}->{name} dex_pc={pc:#x}: {message}' for pc,message in self.inspect(code))
            except (DexError,IndexError,ValueError,struct.error) as exc:
                errors.append(f'{cls}->{name}: {exc}')
        return count,errors

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk');p.add_argument('--prefix',default=None,help='Diagnostic restriction; full scan by default');a=p.parse_args()
    checked=0;errors=[]
    with zipfile.ZipFile(a.apk) as z:
        dexnames=[n for n in z.namelist() if re.fullmatch(r'classes(?:[2-9]\d*|1\d+)?\.dex',n)]
        if not dexnames or 'classes.dex' not in dexnames or len(set(dexnames))!=len(dexnames):raise DexError('Missing or duplicate DEX files')
        for n in dexnames:
            dex=Dex(z.read(n));count,bad=dex.scan(a.prefix);checked+=count
            print(f'{n}: {count} code methods, {len(bad)} findings',flush=True)
            errors.extend(f'{n}: {b}' for b in bad)
            # Avoid keeping full DEX buffers in shared bound-method caches.
            for method in (dex.string,dex.typ,dex.method,dex.param_words):method.cache_clear()
    for e in errors[:100]:print(e,file=sys.stderr)
    if not checked:raise DexError('No methods inspected')
    print(f'DEX static invoke check: {checked} methods, {len(errors)} findings')
    return int(bool(errors))

if __name__=='__main__':sys.exit(main())
