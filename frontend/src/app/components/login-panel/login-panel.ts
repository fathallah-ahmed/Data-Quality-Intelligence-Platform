import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-login-panel',
  imports: [FormsModule],
  templateUrl: './login-panel.html',
  styleUrl: './login-panel.css',
})
export class LoginPanel {
  @Input() username = 'admin';
  @Input() password = '';
  @Input() loggingIn = false;

  @Output() usernameChange = new EventEmitter<string>();
  @Output() passwordChange = new EventEmitter<string>();
  @Output() signIn = new EventEmitter<void>();
}
