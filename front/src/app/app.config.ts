import { registerLocaleData } from '@angular/common';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import localePt from '@angular/common/locales/pt';
import { ApplicationConfig, LOCALE_ID, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { TranslocoHttpLoader } from './transloco-loader';

registerLocaleData(localePt, 'pt-BR');

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    { provide: LOCALE_ID, useValue: 'pt-BR' },
    provideTransloco({
      config: {
        availableLangs: ['pt-BR'],
        defaultLang: 'pt-BR',
        reRenderOnLangChange: true,
        prodMode: false,
      },
      loader: TranslocoHttpLoader,
    }),
  ],
};
