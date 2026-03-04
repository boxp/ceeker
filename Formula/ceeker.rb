# typed: false
# frozen_string_literal: true

class Ceeker < Formula
  desc "AI Coding Agent session & progress monitoring TUI"
  homepage "https://github.com/boxp/ceeker"
  license "MIT"
  version "0.0.8"

  on_macos do
    on_arm do
      url "https://github.com/boxp/ceeker/releases/download/v#{version}/ceeker-darwin-arm64.tar.gz"
      sha256 "040c12266c750ea9cad8c5ef72baa37a2babbffaca5f3c1a84c88a752b6eaa65"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/boxp/ceeker/releases/download/v#{version}/ceeker-linux-arm64.tar.gz"
      sha256 "f9d9980088675c4146346174a457c27d6008de58fd4b63420e9c23ce7c562a9f"
    end

    on_intel do
      url "https://github.com/boxp/ceeker/releases/download/v#{version}/ceeker-linux-amd64.tar.gz"
      sha256 "f5175e0ab6075b8bd2eaebecbc1c7a864de945aca3943efb0757b896f8639ae1"
    end
  end

  def install
    if OS.mac? && Hardware::CPU.arm?
      bin.install "ceeker-darwin-arm64" => "ceeker"
    elsif OS.linux? && Hardware::CPU.arm?
      bin.install "ceeker-linux-arm64" => "ceeker"
    elsif OS.linux? && Hardware::CPU.intel?
      bin.install "ceeker-linux-amd64" => "ceeker"
    end
  end

  test do
    assert_match "ceeker", shell_output("#{bin}/ceeker --help 2>&1", 0)
  end
end
