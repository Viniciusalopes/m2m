import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, TranslocoPipe],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {}
