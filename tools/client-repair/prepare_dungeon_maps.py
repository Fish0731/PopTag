"""Stage CN057 Tomb01..22 terrain with stable existing object indexes."""
from pathlib import Path
import sys,struct,json,hashlib
sys.path.insert(0,r'D:\GAME\work\PopTag-monster-port')
from audit_shop324_compat import CN057,load_index,read_asset
from fix_map_object_indices import objects,decrypt,encrypt,crc
from repair_dungeon_spawn_tables import tile_offset
from rewrite_fx_index import write_index
ROOT=Path(__file__).resolve().parent;OUT=ROOT/'port-audit/dungeon057'
DATA=ROOT/'server/src/main/resources/data'
sha=lambda b:hashlib.sha256(b).hexdigest()
def main():
    OUT.mkdir(parents=True,exist_ok=True)
    assert not (OUT/'manifest.json').exists(),'Prepared candidate already exists'
    client=ROOT/'client';idx,key=load_index(client/'fx.idx');source,_=load_index(CN057/'fx.idx')
    old=objects(client,idx);src=objects(CN057,source);dest={n:i for i,n in enumerate(old)};names=list(src)
    append=bytearray();size=(client/'fx.idd').stat().st_size;changes=[];bounds=[]
    for num in range(1,23):
        board=f'Tomb{num:02d}';name='Map/'+board;entry=source[name]
        plain=decrypt(read_asset(CN057,entry,'fx'),entry['flags']);converted=bytearray(plain)
        width,height,capacity=struct.unpack_from('<III',plain,12);assert (width,height)==(15,13)
        grid=tile_offset(plain,capacity);end=grid+width*height*8
        assert end<=len(plain)
        deps=set()
        for off in range(grid,end,4):
            model=struct.unpack_from('<I',plain,off)[0]
            if model==0xffffffff:continue
            dep=names[model];assert dep in dest,(board,dep);deps.add(dep)
            struct.pack_into('<I',converted,off,dest[dep])
        assert converted[:grid]==plain[:grid] and converted[end:]==plain[end:]
        converted+=bytes((-len(converted))%4);payload=encrypt(converted,entry['flags'])
        assert decrypt(payload,entry['flags'])==converted
        idx[name]={**entry,'offset':size+len(append),'size':len(payload),'key':crc(converted,entry['flags'])};append+=payload
        changes.append(dict(name=name,sourceSha256=sha(plain),convertedSha256=sha(converted),dependencies=sorted(deps)))
        # Original minimaps are UI images, not map object tables.
        icon='Etc/'+board+'_mm';raw=read_asset(CN057,source[icon],'fx')
        idx[icon]={**source[icon],'offset':size+len(append)};append+=raw
        bounds.append(f'{board}\t{width}\t{height}')
    native=[n[4:] for n in sorted(idx,key=str.lower) if n.startswith('Map/')]
    assert len(native)==264
    (OUT/'fx.idx.before').write_bytes((client/'fx.idx').read_bytes());write_index(OUT/'fx.idx.candidate',idx,key)
    (OUT/'fx.append.bin').write_bytes(append)
    (OUT/'ported-native-map-indices.tsv').write_text(''.join(f'{i}\t{n}\n' for i,n in enumerate(native)),encoding='utf-8')
    base=json.loads((DATA/'fxdynmapinfo.json').read_text(encoding='utf-8-sig'))['records']
    additions=json.loads((DATA/'local-map-expansion.json').read_text(encoding='utf-8-sig'))
    existing={r['name'] for r in base+additions['records'] if r['flag']==0};nativeid={n:i for i,n in enumerate(native)}
    template=next(r for r in base if r['name']=='Tomb12')
    added=[]
    for num in range(1,23):
        name=f'Tomb{num:02d}'
        if name in existing:continue
        added.append({**template,'flag':0,'id':0x7000+num,'name':name,'display':f'Tomb {num:02d}','sort_order':nativeid[name]})
    # One visible group entry, alongside the concrete boards.
    if 'TombRandom' not in existing:
        added.append({**template,'flag':0,'id':0x70ff,'name':'TombRandom','display':'Tomb Random','subtype':1,'stage':'none','sort_order':0})
    additions['records']+=added
    assert len(additions['records'])==98
    (OUT/'local-map-expansion.json').write_text(json.dumps(additions,ensure_ascii=False,indent=2),encoding='utf-8')
    (OUT/'local-map-dimensions.tsv').write_text((DATA/'local-map-dimensions.tsv').read_text(encoding='utf-8-sig').rstrip()+'\n'+'\n'.join(bounds)+'\n',encoding='utf-8')
    loc=ROOT/'server/src/main/resources/localization/zh-CN-map-names.tsv'
    (OUT/'zh-CN-map-names.tsv').write_text(loc.read_text(encoding='utf-8-sig').rstrip()+'\nTombRandom\t地牢\n'+''.join(f'Tomb{n:02d}\t地牢{n:02d}\n' for n in range(1,23) if n!=12),encoding='utf-8')
    report=dict(status='PREPARED ONLY',source=str(CN057),maps=changes,originalArchiveBytes=size,appendBytes=len(append),beforeIndexSha256=sha((client/'fx.idx').read_bytes()),candidateIndexSha256=sha((OUT/'fx.idx.candidate').read_bytes()),existingObjectIndexesPreserved=True,originalMapPayloadsUnchanged=True,gameplayVerified=False)
    (OUT/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Prepared 22 terrain maps, 22 original minimaps, native index/catalog candidates; no runtime assets changed')
if __name__=='__main__':main()
