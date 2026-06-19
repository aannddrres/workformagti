# -*- coding: utf-8 -*-
import re
with open('login.html', 'r', encoding='utf-8') as f:
    content = f.read()
old_label = r'<div>\s*<label for="password" class="mb-1.5 block text-sm font-medium text-gray-700">პაროლი</label>\s*<input type="password" id="password"'
new_label = r'''<div>
                    <div class="flex items-center justify-between mb-1.5">
                        <label for="password" class="block text-sm font-medium text-gray-700">პაროლი</label>
                        <a href="#" onclick="openForgotPasswordModal(event)" class="text-sm font-medium text-[#CC0000] hover:underline">დსგავიყდას პაროლ჈?</a>
                    </div>
                    <input type="password" id="password"'''
content = re.sub(old_label, new_label, content)
modal_html = r'''
    <!-- Forgot Password Modal -->
    <div id="forgot-password-modal" class="fixed inset-0 z-50 hidden bg-gray-900/50 backdrop-blur-sm transition-opacity flex items-center justify-center">
        <div class="bg-white rounded-2xl shadow-xl w-full max-w-md p-6 transform transition-all scale-100 opacity-100 m-4">
            <div class="flex justify-between items-center mb-4">
                <h3 class="text-xl font-bold text-gray-900">პაროლის სჩდგბნა</h3>
                <button onclick="closeForgotPasswordModal()" class="text-gray-400 hover:text-gray-600 focus:outline-none">
                    <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                    </svg>
                </button>
            </div>
            <form id="forgot-password-form" onsubmit="submitForgotPassword(event)" class="space-y-4">
                <div>
                    <label for="reset-email" class="mb-1.5 block text-sm font-medium text-gray-700">ელ. ყოსტა</label>
                    <input type="email" id="reset-email" required
                        class="w-full rounded-xl border border-gray-300 px-4 py-3 text-sm text-gray-800 focus:border-[#CC0000] focus:outline-none focus:ring-1 focus:ring-[#CC0000]"
                        placeholder="შეისვანეე ელ. პოსტა" />
                </div>
                <div id="forgot-message" class="hidden text-sm font-medium"></div>
                <button type="submit" class="w-full rounded-xl bg-[#CC0000] px-4 py-3.5 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-red-700">
                    რკადგენის ბზულის გაგზავნა
                </button>
            </form>
        </div>
    </div>
    
    <script>
        function openForgotPasswordModal(e) {
            if(e) e.preventDefault();
            document.getElementById('forgot-password-modal').classList.remove('hidden');
            document.getElementById('reset-email').value = '';
            document.getElementById('forgot-message').classList.add('hidden');
        }
        function closeForgotPasswordModal() {
            document.getElementById('forgot-password-modal').classList.add('hidden');
        }
        async function submitForgotPassword(event) {
            event.preventDefault();
            const email = document.getElementById('reset-email').value;
            const msgDiv = document.getElementById('forgot-message');
            msgDiv.classList.add('hidden');
            msgDiv.classList.remove('text-green-600', 'text-red-600');
            try {
                const response = await fetch('http://127.0.0.1:8000/api/auth/forgot-password', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email })
                });
                const data = await response.json();
                if (!response.ok) throw new Error(data.detail || 'ი�ესზომა პაროლის აუდგენის ას');
                msgDiv.textContent = data.message || 'აუდგენის ინუტრუქცია გამოგზავნილია ელ. პოსტაზ�';
                msgDiv.classList.add('text-green-600');
                msgDiv.classList.remove('hidden');
            } catch(e) {
                msgDiv.textContent = e.message;
                msgDiv.classList.add('text-red-600');
                msgDiv.classList.remove('hidden');
            }
        }
'''
content = content.replace('<script>', modal_html)
with open('login.html', 'w', encoding='utf-8') as f:
    f.write(content)
print('Patched login.html')
