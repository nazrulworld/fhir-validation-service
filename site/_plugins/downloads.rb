# frozen_string_literal: true

# Spec 011: publishes the repository files the converter copied to _downloads/ byte for byte, at the same path
# without the _downloads/ prefix (e.g. _downloads/reference/.env.example -> /reference/.env.example).
# Jekyll would otherwise skip dot-files and read a file that starts with "---" as front matter.
# The list comes from docs_downloads in _config.generated.yml (scripts/build-docs-site.py).
module DocsSite
  class Downloads < Jekyll::Generator
    safe true
    priority :highest

    def generate(site)
      base = File.join(site.source, "_downloads")
      Array(site.config["docs_downloads"]).each do |rel|
        dir, name = File.split(rel)
        site.static_files << Jekyll::StaticFile.new(site, base, "/#{dir}", name)
      end
    end
  end
end
