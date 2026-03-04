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
      sha256 "PLACEHOLDER"
    end
  end

  on_linux do
    on_arm do
      url "https://github.com/boxp/ceeker/releases/download/v#{version}/ceeker-linux-arm64.tar.gz"
      sha256 "PLACEHOLDER"
    end

    on_intel do
      url "https://github.com/boxp/ceeker/releases/download/v#{version}/ceeker-linux-amd64.tar.gz"
      sha256 "PLACEHOLDER"
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
