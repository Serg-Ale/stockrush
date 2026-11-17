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
      <mat-icon aria-hidden="true">inventory_2</mat-icon>
      <span class="title">Stockrush</span>
      <nav>
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
    .title { margin-left: 8px; font-weight: 500; }
    nav { margin-left: 24px; display: flex; gap: 4px; }
    .active { background: var(--mat-sys-secondary-container); }
    .spacer { flex: 1; }
    main { max-width: 960px; margin: 0 auto; padding: 24px 16px; }
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
