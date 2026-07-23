# frozen_string_literal: true

require "open3"
require "json"

# pdfgate CLI の薄いラッパー。
# タイムアウト時は子プロセスを確実に kill する（Timeout.timeout では
# 子プロセスが生き残るため、wait_thr.join ベースで実装している）。
class Pdfgate
  Result = Struct.new(:ok, :exit_code, :issues, :error, keyword_init: true) do
    alias_method :ok?, :ok

    def deny_codes
      issues.select { |i| i["severity"] == "deny" }.map { |i| i["code"] }
    end

    def warn_codes
      issues.select { |i| i["severity"] == "warn" }.map { |i| i["code"] }
    end
  end

  BIN = ENV.fetch("PDFGATE_BIN", "pdfgate")

  # @return [Result] ok?=true は「ポリシー違反なし」。
  #   error には "timeout" / "parse-failure" / "password-required" などが入る
  def self.validate(path, policy: nil, timeout: 20)
    args = [BIN, "validate", path.to_s]
    args.push("--policy", policy.to_s) if policy

    stdout, status = run_with_timeout(args, timeout)
    return Result.new(ok: false, exit_code: nil, issues: [], error: "timeout") if status.nil?

    body = begin
      JSON.parse(stdout.to_s)
    rescue JSON::ParserError
      {}
    end

    case status.exitstatus
    when 0, 1
      Result.new(
        ok: status.exitstatus.zero?,
        exit_code: status.exitstatus,
        issues: body.fetch("issues", []),
        error: nil
      )
    else
      Result.new(
        ok: false,
        exit_code: status.exitstatus,
        issues: [],
        error: body.dig("error", "code") || "parse-failure"
      )
    end
  end

  def self.run_with_timeout(args, timeout)
    Open3.popen2(*args) do |stdin, stdout, wait_thr|
      stdin.close
      reader = Thread.new { stdout.read }
      if wait_thr.join(timeout)
        [reader.value, wait_thr.value]
      else
        begin
          Process.kill("KILL", wait_thr.pid)
        rescue Errno::ESRCH
          nil
        end
        wait_thr.join
        [nil, nil]
      end
    end
  end
  private_class_method :run_with_timeout
end
