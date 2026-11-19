import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../auth/auth.service';

/** Frame of every page that needs a signed-in user. */
@Component({
  selector: 'app-shell',
  imports: [MatToolbarModule, MatButtonModule, MatIconModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <mat-toolbar>
      <a class="brand" routerLink="/" aria-label="Stockrush, início">
        <span class="mark"><mat-icon aria-hidden="true">inventory_2</mat-icon></span>
        <span class="title">Stockrush</span>
      </a>
      <nav aria-label="Principal">
        <a matButton routerLink="/catalogo" routerLinkActive="active">Catálogo</a>
        <a matButton routerLink="/pedidos" routerLinkActive="active">Pedidos</a>
        @if (auth.isAdmin()) {
          <a matButton routerLink="/painel" routerLinkActive="active">Painel</a>
        }
      </nav>
      <span class="spacer"></span>
      <button matButton (click)="logout()">Sair</button>
    </mat-toolbar>
    <main><router-outlet /></main>
  `,
  styles: `
    mat-toolbar {
      position: sticky;
      top: 0;
      z-index: 10;
      gap: 4px;
      background: color-mix(in srgb, var(--mat-sys-surface-container) 92%, transparent);
      backdrop-filter: blur(12px);
      border-bottom: 1px solid var(--sr-hairline);
    }
    .brand { display: flex; align-items: center; gap: 12px; color: inherit; text-decoration: none; }
    .mark {
      display: grid;
      place-items: center;
      width: 36px;
      height: 36px;
      border-radius: 12px;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .title { font: var(--mat-sys-title-large); letter-spacing: -0.02em; }
    nav { margin-left: 24px; display: flex; gap: 4px; }
    .active { background: var(--mat-sys-secondary-container); }
    .spacer { flex: 1; }
    main { max-width: 1040px; margin: 0 auto; padding: 40px 24px 64px; }
    @media (max-width: 600px) {
      .title { display: none; }
      nav { margin-left: 8px; }
      main { padding: 24px 16px 48px; }
    }
  `,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  logout(): void {
    this.auth.logout();
    void this.router.navigate(['/entrar']);
  }
}
