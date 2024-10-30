import React from 'react';
import { Twitter, Send } from 'lucide-react';

export function Join() {
  return (
    <section id="join" className="py-20 relative overflow-hidden">
      <div className="container mx-auto px-6 relative">
        <div className="max-w-4xl mx-auto">
          <div className="bg-white rounded-2xl p-8 shadow-lg mb-12">
            <h2 className="text-4xl font-bold text-emerald-800 text-center">
              Join the Digital Forest
            </h2>
            <p className="text-emerald-700 text-center mt-4">
              Connect with our growing network of eco-conscious pioneers
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
            {[
              {
                icon: Twitter,
                name: 'Twitter',
                link: 'https://x.com/MeiMeiMemecoin',
                color: 'bg-blue-500'
              },
              {
                icon: Send,
                name: 'Telegram',
                link: 'https://t.me/MeiMei_ETH',
                color: 'bg-sky-500'
              }
            ].map((platform) => (
              <a
                key={platform.name}
                href={platform.link}
                target="_blank"
                rel="noopener noreferrer"
                className="bg-white rounded-2xl p-8 shadow-lg transition-all transform hover:-translate-y-1 text-center"
              >
                <div className={`w-16 h-16 ${platform.color} rounded-full flex items-center justify-center mx-auto mb-4`}>
                  <platform.icon className="w-8 h-8 text-white" />
                </div>
                <h3 className="text-xl font-semibold text-emerald-800">
                  {platform.name}
                </h3>
              </a>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}