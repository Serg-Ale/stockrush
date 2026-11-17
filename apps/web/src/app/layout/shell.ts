import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterOutlet } from '@angular/router';
import { AuthService } from '../auth/auth.service';

/** Frame of every page that needs a signed-in user. */
@Component({
  selector: 'app-shell',
  imports: [MatToolbarModule, MatButtonModule, MatIconModule, RouterOutlet],
  template: `
    <mat-toolbar>
      <mat-icon aria-hidden="true">inventory_2</mat-icon>
      <span class="title">Stockrush</span>
      <span class="spacer"></span>
      <button matButton (click)="logout()">Sair</button>
    </mat-toolbar>
    <main><router-outlet /></main>
  `,
  styles: `
    .title { margin-left: 8px; font-weight: 500; }
    .spacer { flex: 1; }
    main { max-width: 960px; margin: 0 auto; padding: 24px 16px; }
  `,
})
export class Shell {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  logout(): void {
    this.auth.logout();
    void this.router.navigate(['/entrar']);
  }
}
