import { registerLocaleData } from '@angular/common';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import localePt from '@angular/common/locales/pt';
import {
  ApplicationConfig,
  inject,
  Injectable,
  LOCALE_ID,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideTransloco, Translation, TranslocoLoader } from '@jsverse/transloco';
import { instaladorInterceptor } from './api';

registerLocaleData(localePt, 'pt-BR');

@Injectable({ providedIn: 'root' })
export class TraducoesInstalador implements TranslocoLoader {
  private http = inject(HttpClient);

  getTranslation(lang: string) {
    return this.http.get<Translation>(`/i18n/${lang}.json`);
  }
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([instaladorInterceptor])),
    { provide: LOCALE_ID, useValue: 'pt-BR' },
    provideTransloco({
      config: {
        availableLangs: ['pt-BR'],
        defaultLang: 'pt-BR',
        reRenderOnLangChange: true,
        prodMode: false,
      },
      loader: TraducoesInstalador,
    }),
  ],
};
