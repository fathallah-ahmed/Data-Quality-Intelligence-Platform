import { Component, EventEmitter, Input, Output } from '@angular/core';

@Component({
  selector: 'app-top-bar',
  imports: [],
  templateUrl: './top-bar.html',
  styleUrl: './top-bar.css',
})
export class TopBar {
  @Input() refreshing = false;
  @Input() authenticated = false;

  @Output() refresh = new EventEmitter<void>();
  @Output() logout = new EventEmitter<void>();
}
