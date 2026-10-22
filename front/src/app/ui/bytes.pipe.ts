import { Pipe, PipeTransform } from '@angular/core';

/** 1536 → "1,5 KiB" (formato pt-BR). */
@Pipe({ name: 'bytes' })
export class BytesPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (value === null || value === undefined) {
      return '—';
    }
    const unidades = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let v = value;
    let i = 0;
    while (v >= 1024 && i < unidades.length - 1) {
      v /= 1024;
      i++;
    }
    const texto =
      i === 0 ? String(Math.round(v)) : v.toLocaleString('pt-BR', { maximumFractionDigits: 1 });
    return `${texto} ${unidades[i]}`;
  }
}
