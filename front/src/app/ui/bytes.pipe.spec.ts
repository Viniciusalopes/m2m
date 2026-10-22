import { BytesPipe } from './bytes.pipe';

describe('BytesPipe', () => {
  const pipe = new BytesPipe();

  it('formata em unidades binárias com vírgula decimal', () => {
    expect(pipe.transform(0)).toBe('0 B');
    expect(pipe.transform(1023)).toBe('1023 B');
    expect(pipe.transform(1536)).toBe('1,5 KiB');
    expect(pipe.transform(5 * 1024 * 1024)).toBe('5 MiB');
    expect(pipe.transform(null)).toBe('—');
  });
});
