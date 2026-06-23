enum ModuleInstallStatus {
  pending,
  downloading,
  installing,
  installed,
  failed,
  cancelled,
}

class ModuleInstallState {
  const ModuleInstallState({
    required this.status,
    this.progress = 0,
    this.errorMessage,
  });

  final ModuleInstallStatus status;
  final double progress;
  final String? errorMessage;

  bool get isInstalled => status == ModuleInstallStatus.installed;
}
