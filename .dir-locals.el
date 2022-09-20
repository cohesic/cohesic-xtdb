((nil . ((eval . (progn
                   (setq-local cider-clojure-cli-aliases "-A:build")
                   (projectile-update-project-type
                     'clojure-cli
                     :precedence 'high
                     :test "bb test"))))))
