import UIKit
import WebexSDK

/// Rassd-themed brand palette mirrored from the Flutter app (AppColors).
enum RassdTheme {
    static let primary = UIColor(red: 0x26 / 255, green: 0x44 / 255, blue: 0xBC / 255, alpha: 1)
    static let secondary = UIColor(red: 0x0A / 255, green: 0xCE / 255, blue: 0xAE / 255, alpha: 1)
    static let danger = UIColor(red: 0xF4 / 255, green: 0x32 / 255, blue: 0x2C / 255, alpha: 1)
    static let canvas = UIColor(red: 0x0B / 255, green: 0x0E / 255, blue: 0x17 / 255, alpha: 1)
    static let panel = UIColor(red: 0x12 / 255, green: 0x16 / 255, blue: 0x22 / 255, alpha: 1)
    static let subtitle = UIColor(red: 0x9A / 255, green: 0xA3 / 255, blue: 0xAE / 255, alpha: 1)
}

/// Abstraction over in-meeting chat delivery. Webex SDKs cannot carry real-time
/// Meeting Center chat, so this seam lets a real backend (e.g. SignalR) be wired
/// later. The default echoes locally for testing.
protocol ChatTransport: AnyObject {
    func start(meetingId: String?, onMessage: @escaping (_ sender: String, _ text: String, _ fromMe: Bool) -> Void)
    func send(_ text: String)
    func stop()
}

final class LocalEchoChatTransport: ChatTransport {
    private var onMessage: ((String, String, Bool) -> Void)?

    func start(meetingId: String?, onMessage: @escaping (String, String, Bool) -> Void) {
        self.onMessage = onMessage
    }

    func send(_ text: String) {
        onMessage?("You", text, true)
        onMessage?("Echo", text, false)
    }

    func stop() {
        onMessage = nil
    }
}

/// Full in-meeting screen for Webex on iOS: active-speaker video, self preview,
/// mic / camera / switch-camera / participants / chat / leave controls, a live
/// participant roster and a chat panel.
final class WebexMeetingViewController: UIViewController {
    // Render views handed to the Webex SDK.
    let remoteVideoView = MediaRenderView()
    let selfVideoView = MediaRenderView()

    // Hooks the plugin uses to forward call state to Flutter.
    var onConnectedHook: (() -> Void)?
    var onEndedHook: ((_ callId: String?) -> Void)?

    var meetingTitle: String = "Webex Meeting"
    var isAudioOnly: Bool = false

    private weak var call: Call?
    private var isMuted = false
    private var isCameraOn = true
    private var isFrontCamera = true

    private let chat: ChatTransport = LocalEchoChatTransport()
    private var chatMessages: [(sender: String, text: String, fromMe: Bool)] = []
    private var participants: [CallMembership] = []

    // MARK: UI
    private let topBar = GradientBar()
    private let titleLabel = UILabel()
    private let statusLabel = UILabel()

    private let controlsBar = UIView()
    private lazy var micButton = makeControlButton(systemName: "mic.fill", action: #selector(toggleMute))
    private lazy var cameraButton = makeControlButton(systemName: "video.fill", action: #selector(toggleCamera))
    private lazy var switchButton = makeControlButton(systemName: "arrow.triangle.2.circlepath.camera.fill", action: #selector(switchCamera))
    private lazy var participantsButton = makeControlButton(systemName: "person.2.fill", action: #selector(toggleParticipants))
    private lazy var chatButton = makeControlButton(systemName: "bubble.left.and.bubble.right.fill", action: #selector(toggleChat))
    private lazy var leaveButton = makeControlButton(systemName: "phone.down.fill", action: #selector(leaveMeeting), danger: true)

    private let participantsPanel = UIView()
    private let participantsTable = UITableView(frame: .zero, style: .plain)
    private let participantsTitle = UILabel()

    private let chatPanel = UIView()
    private let chatTable = UITableView(frame: .zero, style: .plain)
    private let chatInput = UITextField()
    private var chatPanelBottom: NSLayoutConstraint!

    override var prefersStatusBarHidden: Bool { false }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = RassdTheme.canvas
        setupVideoViews()
        setupTopBar()
        setupControlsBar()
        setupParticipantsPanel()
        setupChatPanel()
        chat.start(meetingId: meetingTitle) { [weak self] sender, text, fromMe in
            DispatchQueue.main.async { self?.appendChat(sender: sender, text: text, fromMe: fromMe) }
        }
        registerKeyboardObservers()
        let tap = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboard))
        tap.cancelsTouchesInView = false
        tap.delegate = self
        view.addGestureRecognizer(tap)
        applyControlState()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    private func registerKeyboardObservers() {
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(keyboardWillChange(_:)),
                           name: UIResponder.keyboardWillShowNotification, object: nil)
        center.addObserver(self, selector: #selector(keyboardWillChange(_:)),
                           name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        center.addObserver(self, selector: #selector(keyboardWillHide(_:)),
                           name: UIResponder.keyboardWillHideNotification, object: nil)
    }

    @objc private func keyboardWillChange(_ note: Notification) {
        guard !chatPanel.isHidden,
              let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
        let overlap = max(0, frame.height - view.safeAreaInsets.bottom)
        chatPanelBottom.constant = -overlap
        animateKeyboard(note)
    }

    @objc private func keyboardWillHide(_ note: Notification) {
        chatPanelBottom.constant = 0
        animateKeyboard(note)
    }

    private func animateKeyboard(_ note: Notification) {
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        UIView.animate(withDuration: duration) { self.view.layoutIfNeeded() }
    }

    @objc private func dismissKeyboard() {
        view.endEditing(true)
    }

    // MARK: Public wiring used by the plugin

    func attach(call: Call) {
        self.call = call
        call.videoRenderViews = (selfVideoView, remoteVideoView)
        isMuted = !call.sendingAudio
        isCameraOn = call.sendingVideo
        registerCallbacks(call)
        applyControlState()
        refreshRoster()
    }

    func handleDialFailed(_ message: String) {
        statusLabel.text = message
    }

    // MARK: Setup

    private func setupVideoViews() {
        remoteVideoView.translatesAutoresizingMaskIntoConstraints = false
        remoteVideoView.backgroundColor = RassdTheme.canvas
        remoteVideoView.isHidden = isAudioOnly
        view.addSubview(remoteVideoView)
        NSLayoutConstraint.activate([
            remoteVideoView.topAnchor.constraint(equalTo: view.topAnchor),
            remoteVideoView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            remoteVideoView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            remoteVideoView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        if isAudioOnly {
            let audioLabel = UILabel()
            audioLabel.translatesAutoresizingMaskIntoConstraints = false
            audioLabel.text = "Audio meeting"
            audioLabel.textColor = .white
            audioLabel.font = .systemFont(ofSize: 22, weight: .semibold)
            view.addSubview(audioLabel)
            NSLayoutConstraint.activate([
                audioLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                audioLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            ])
        }

        selfVideoView.translatesAutoresizingMaskIntoConstraints = false
        selfVideoView.backgroundColor = RassdTheme.panel
        selfVideoView.layer.cornerRadius = 14
        selfVideoView.layer.masksToBounds = true
        selfVideoView.layer.borderWidth = 2
        selfVideoView.layer.borderColor = RassdTheme.secondary.cgColor
        selfVideoView.isHidden = isAudioOnly
        view.addSubview(selfVideoView)
        NSLayoutConstraint.activate([
            selfVideoView.widthAnchor.constraint(equalToConstant: 104),
            selfVideoView.heightAnchor.constraint(equalToConstant: 150),
            selfVideoView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            selfVideoView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 84),
        ])
    }

    private func setupTopBar() {
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = meetingTitle
        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 17, weight: .bold)
        titleLabel.lineBreakMode = .byTruncatingMiddle

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.text = "Connecting…"
        statusLabel.textColor = UIColor.white.withAlphaComponent(0.85)
        statusLabel.font = .systemFont(ofSize: 13, weight: .regular)

        topBar.addSubview(titleLabel)
        topBar.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: view.topAnchor),
            topBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            titleLabel.leadingAnchor.constraint(equalTo: topBar.leadingAnchor, constant: 18),
            titleLabel.trailingAnchor.constraint(equalTo: topBar.trailingAnchor, constant: -18),
            titleLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),

            statusLabel.leadingAnchor.constraint(equalTo: topBar.leadingAnchor, constant: 18),
            statusLabel.trailingAnchor.constraint(equalTo: topBar.trailingAnchor, constant: -18),
            statusLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 2),
            statusLabel.bottomAnchor.constraint(equalTo: topBar.bottomAnchor, constant: -12),
        ])
    }

    private func setupControlsBar() {
        controlsBar.translatesAutoresizingMaskIntoConstraints = false
        controlsBar.backgroundColor = RassdTheme.panel.withAlphaComponent(0.92)
        controlsBar.layer.cornerRadius = 32
        view.addSubview(controlsBar)

        let stack = UIStackView(arrangedSubviews: [micButton, cameraButton, switchButton, participantsButton, chatButton, leaveButton])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .horizontal
        stack.distribution = .equalSpacing
        stack.alignment = .center
        controlsBar.addSubview(stack)

        NSLayoutConstraint.activate([
            controlsBar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            controlsBar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            controlsBar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -10),
            controlsBar.heightAnchor.constraint(equalToConstant: 80),

            stack.leadingAnchor.constraint(equalTo: controlsBar.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: controlsBar.trailingAnchor, constant: -14),
            stack.centerYAnchor.constraint(equalTo: controlsBar.centerYAnchor),
        ])
    }

    private func setupParticipantsPanel() {
        configureSheet(participantsPanel)
        participantsPanel.isHidden = true

        participantsTitle.translatesAutoresizingMaskIntoConstraints = false
        participantsTitle.text = "Participants"
        participantsTitle.textColor = .white
        participantsTitle.font = .systemFont(ofSize: 18, weight: .bold)

        let closeButton = makeCloseButton(action: #selector(toggleParticipants))

        participantsTable.translatesAutoresizingMaskIntoConstraints = false
        participantsTable.backgroundColor = .clear
        participantsTable.separatorColor = UIColor.white.withAlphaComponent(0.08)
        participantsTable.dataSource = self
        participantsTable.delegate = self
        participantsTable.register(UITableViewCell.self, forCellReuseIdentifier: "participant")
        participantsTable.rowHeight = 60

        participantsPanel.addSubview(participantsTitle)
        participantsPanel.addSubview(closeButton)
        participantsPanel.addSubview(participantsTable)

        NSLayoutConstraint.activate([
            participantsTitle.leadingAnchor.constraint(equalTo: participantsPanel.leadingAnchor, constant: 18),
            participantsTitle.topAnchor.constraint(equalTo: participantsPanel.topAnchor, constant: 18),
            closeButton.trailingAnchor.constraint(equalTo: participantsPanel.trailingAnchor, constant: -16),
            closeButton.centerYAnchor.constraint(equalTo: participantsTitle.centerYAnchor),
            participantsTable.topAnchor.constraint(equalTo: participantsTitle.bottomAnchor, constant: 12),
            participantsTable.leadingAnchor.constraint(equalTo: participantsPanel.leadingAnchor),
            participantsTable.trailingAnchor.constraint(equalTo: participantsPanel.trailingAnchor),
            participantsTable.bottomAnchor.constraint(equalTo: participantsPanel.bottomAnchor),
        ])
    }

    private func setupChatPanel() {
        chatPanel.translatesAutoresizingMaskIntoConstraints = false
        chatPanel.backgroundColor = RassdTheme.panel
        chatPanel.layer.cornerRadius = 22
        chatPanel.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        view.addSubview(chatPanel)
        chatPanelBottom = chatPanel.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        NSLayoutConstraint.activate([
            chatPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            chatPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            chatPanelBottom,
            chatPanel.heightAnchor.constraint(equalTo: view.heightAnchor, multiplier: 0.6),
        ])
        chatPanel.isHidden = true

        let chatTitle = UILabel()
        chatTitle.translatesAutoresizingMaskIntoConstraints = false
        chatTitle.text = "Chat"
        chatTitle.textColor = .white
        chatTitle.font = .systemFont(ofSize: 18, weight: .bold)

        let closeButton = makeCloseButton(action: #selector(toggleChat))

        chatTable.translatesAutoresizingMaskIntoConstraints = false
        chatTable.backgroundColor = .clear
        chatTable.separatorStyle = .none
        chatTable.dataSource = self
        chatTable.delegate = self
        chatTable.register(UITableViewCell.self, forCellReuseIdentifier: "chat")
        chatTable.rowHeight = UITableView.automaticDimension
        chatTable.estimatedRowHeight = 44
        chatTable.keyboardDismissMode = .interactive

        let inputBar = UIView()
        inputBar.translatesAutoresizingMaskIntoConstraints = false

        chatInput.translatesAutoresizingMaskIntoConstraints = false
        chatInput.placeholder = "Type a message"
        chatInput.textColor = .white
        chatInput.attributedPlaceholder = NSAttributedString(
            string: "Type a message",
            attributes: [.foregroundColor: RassdTheme.subtitle]
        )
        chatInput.backgroundColor = UIColor.white.withAlphaComponent(0.08)
        chatInput.layer.cornerRadius = 10
        chatInput.setLeftPaddingPoints(12)
        chatInput.returnKeyType = .send
        chatInput.delegate = self

        let sendButton = UIButton(type: .system)
        sendButton.translatesAutoresizingMaskIntoConstraints = false
        sendButton.setTitle("Send", for: .normal)
        sendButton.setTitleColor(.white, for: .normal)
        sendButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        sendButton.backgroundColor = RassdTheme.primary
        sendButton.layer.cornerRadius = 10
        sendButton.contentEdgeInsets = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)
        sendButton.addTarget(self, action: #selector(sendChat), for: .touchUpInside)

        inputBar.addSubview(chatInput)
        inputBar.addSubview(sendButton)

        chatPanel.addSubview(chatTitle)
        chatPanel.addSubview(closeButton)
        chatPanel.addSubview(chatTable)
        chatPanel.addSubview(inputBar)

        NSLayoutConstraint.activate([
            chatTitle.leadingAnchor.constraint(equalTo: chatPanel.leadingAnchor, constant: 18),
            chatTitle.topAnchor.constraint(equalTo: chatPanel.topAnchor, constant: 18),
            closeButton.trailingAnchor.constraint(equalTo: chatPanel.trailingAnchor, constant: -16),
            closeButton.centerYAnchor.constraint(equalTo: chatTitle.centerYAnchor),

            chatTable.topAnchor.constraint(equalTo: chatTitle.bottomAnchor, constant: 12),
            chatTable.leadingAnchor.constraint(equalTo: chatPanel.leadingAnchor, constant: 8),
            chatTable.trailingAnchor.constraint(equalTo: chatPanel.trailingAnchor, constant: -8),

            inputBar.topAnchor.constraint(equalTo: chatTable.bottomAnchor, constant: 8),
            inputBar.leadingAnchor.constraint(equalTo: chatPanel.leadingAnchor, constant: 12),
            inputBar.trailingAnchor.constraint(equalTo: chatPanel.trailingAnchor, constant: -12),
            inputBar.bottomAnchor.constraint(equalTo: chatPanel.bottomAnchor, constant: -12),
            inputBar.heightAnchor.constraint(equalToConstant: 44),

            chatInput.leadingAnchor.constraint(equalTo: inputBar.leadingAnchor),
            chatInput.topAnchor.constraint(equalTo: inputBar.topAnchor),
            chatInput.bottomAnchor.constraint(equalTo: inputBar.bottomAnchor),
            sendButton.leadingAnchor.constraint(equalTo: chatInput.trailingAnchor, constant: 8),
            sendButton.trailingAnchor.constraint(equalTo: inputBar.trailingAnchor),
            sendButton.topAnchor.constraint(equalTo: inputBar.topAnchor),
            sendButton.bottomAnchor.constraint(equalTo: inputBar.bottomAnchor),
        ])
    }

    private func configureSheet(_ panel: UIView) {
        panel.translatesAutoresizingMaskIntoConstraints = false
        panel.backgroundColor = RassdTheme.panel
        panel.layer.cornerRadius = 22
        panel.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        view.addSubview(panel)
        NSLayoutConstraint.activate([
            panel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            panel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            panel.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            panel.heightAnchor.constraint(equalTo: view.heightAnchor, multiplier: 0.55),
        ])
    }

    // MARK: Controls

    @objc private func toggleMute() {
        guard let call = call else { return }
        isMuted.toggle()
        call.sendingAudio = !isMuted
        applyControlState()
    }

    @objc private func toggleCamera() {
        guard let call = call else { return }
        isCameraOn.toggle()
        call.sendingVideo = isCameraOn
        selfVideoView.isHidden = !isCameraOn
        applyControlState()
    }

    @objc private func switchCamera() {
        guard let call = call else { return }
        isFrontCamera.toggle()
        call.facingMode = isFrontCamera ? .user : .environment
    }

    @objc private func toggleParticipants() {
        view.endEditing(true)
        chatPanel.isHidden = true
        participantsPanel.isHidden.toggle()
        if !participantsPanel.isHidden { refreshRoster() }
    }

    @objc private func toggleChat() {
        participantsPanel.isHidden = true
        chatPanel.isHidden.toggle()
        if chatPanel.isHidden { view.endEditing(true) }
    }

    @objc private func leaveMeeting() {
        call?.hangup { _ in }
        dismissMeeting(callId: call?.callId)
    }

    @objc private func sendChat() {
        let text = (chatInput.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        chatInput.text = ""
        chat.send(text)
    }

    private func applyControlState() {
        let muteIcon = isMuted ? "mic.slash.fill" : "mic.fill"
        setIcon(micButton, muteIcon, active: !isMuted)
        let camIcon = isCameraOn ? "video.fill" : "video.slash.fill"
        setIcon(cameraButton, camIcon, active: isCameraOn)
        cameraButton.isHidden = isAudioOnly
        switchButton.isHidden = isAudioOnly
    }

    // MARK: Call callbacks

    private func registerCallbacks(_ call: Call) {
        call.onConnected = { [weak self] in
            // Required so the SDK takes over the audio session and routes call
            // media (mic capture + incoming audio). Without this the session
            // can stay on a playback-only route and no call audio flows.
            call.updateAudioSession()
            DispatchQueue.main.async {
                self?.isMuted = !call.sendingAudio
                self?.isCameraOn = call.sendingVideo
                self?.statusLabel.text = "Connected"
                self?.applyControlState()
                self?.onConnectedHook?()
                self?.refreshRoster()
            }
        }
        call.onMediaChanged = { [weak self] _ in
            DispatchQueue.main.async { self?.refreshRoster() }
        }
        call.onCallMembershipChanged = { [weak self] _ in
            DispatchQueue.main.async { self?.refreshRoster() }
        }
        call.onFailed = { [weak self] reason in
            DispatchQueue.main.async {
                self?.statusLabel.text = "Call failed"
                self?.dismissMeeting(callId: call.callId, reason: "\(reason)")
            }
        }
        call.onDisconnected = { [weak self] _ in
            DispatchQueue.main.async { self?.dismissMeeting(callId: call.callId) }
        }
    }

    private func dismissMeeting(callId: String?, reason: String? = nil) {
        chat.stop()
        onEndedHook?(callId)
        onEndedHook = nil
        if presentingViewController != nil {
            dismiss(animated: true)
        }
    }

    // MARK: Roster

    private func refreshRoster() {
        participants = (call?.memberships ?? []).filter { $0.state == .joined || $0.state == .waiting }
        participantsTitle.text = "Participants (\(participants.count))"
        statusLabel.text = participants.isEmpty ? statusLabel.text : "Connected · \(participants.count) in meeting"
        participantsTable.reloadData()
    }

    private func appendChat(sender: String, text: String, fromMe: Bool) {
        chatMessages.append((sender, text, fromMe))
        chatTable.reloadData()
        let last = IndexPath(row: chatMessages.count - 1, section: 0)
        chatTable.scrollToRow(at: last, at: .bottom, animated: true)
    }

    // MARK: Button factories

    private func makeControlButton(systemName: String, action: Selector, danger: Bool = false) -> UIButton {
        let button = UIButton(type: .system)
        button.translatesAutoresizingMaskIntoConstraints = false
        let cfg = UIImage.SymbolConfiguration(pointSize: 19, weight: .semibold)
        button.setImage(UIImage(systemName: systemName, withConfiguration: cfg), for: .normal)
        button.tintColor = .white
        button.backgroundColor = danger ? RassdTheme.danger : UIColor.white.withAlphaComponent(0.12)
        button.layer.cornerRadius = 27
        button.addTarget(self, action: action, for: .touchUpInside)
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(equalToConstant: 54),
            button.heightAnchor.constraint(equalToConstant: 54),
        ])
        return button
    }

    private func setIcon(_ button: UIButton, _ systemName: String, active: Bool) {
        let cfg = UIImage.SymbolConfiguration(pointSize: 19, weight: .semibold)
        button.setImage(UIImage(systemName: systemName, withConfiguration: cfg), for: .normal)
        button.backgroundColor = active ? UIColor.white.withAlphaComponent(0.12) : RassdTheme.primary
    }

    private func makeCloseButton(action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.translatesAutoresizingMaskIntoConstraints = false
        let cfg = UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold)
        button.setImage(UIImage(systemName: "xmark.circle.fill", withConfiguration: cfg), for: .normal)
        button.tintColor = RassdTheme.subtitle
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }
}

extension WebexMeetingViewController: UITableViewDataSource, UITableViewDelegate {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        tableView == participantsTable ? participants.count : chatMessages.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        if tableView == participantsTable {
            let cell = tableView.dequeueReusableCell(withIdentifier: "participant", for: indexPath)
            let member = participants[indexPath.row]
            cell.backgroundColor = .clear
            cell.selectionStyle = .none
            var content = cell.defaultContentConfiguration()
            let name = (member.displayName?.isEmpty == false ? member.displayName! : "Participant")
                + (member.isSelf ? " (You)" : "")
            content.text = name
            content.textProperties.color = member.isActiveSpeaker ? RassdTheme.secondary : .white
            content.textProperties.font = .systemFont(ofSize: 16, weight: .semibold)
            let mic = member.sendingAudio ? "Mic on" : "Mic off"
            let cam = member.sendingVideo ? "Cam on" : "Cam off"
            content.secondaryText = "\(mic)  ·  \(cam)"
            content.secondaryTextProperties.color = RassdTheme.subtitle
            content.secondaryTextProperties.font = .systemFont(ofSize: 13)
            content.image = UIImage(systemName: member.sendingAudio ? "mic.fill" : "mic.slash.fill")
            content.imageProperties.tintColor = member.sendingAudio ? RassdTheme.secondary : RassdTheme.subtitle
            cell.contentConfiguration = content
            return cell
        }

        let cell = tableView.dequeueReusableCell(withIdentifier: "chat", for: indexPath)
        let message = chatMessages[indexPath.row]
        cell.backgroundColor = .clear
        cell.selectionStyle = .none
        var content = cell.defaultContentConfiguration()
        content.text = message.sender
        content.textProperties.color = message.fromMe ? RassdTheme.secondary : RassdTheme.primary
        content.textProperties.font = .systemFont(ofSize: 12, weight: .bold)
        content.secondaryText = message.text
        content.secondaryTextProperties.color = .white
        content.secondaryTextProperties.font = .systemFont(ofSize: 15)
        cell.contentConfiguration = content
        return cell
    }
}

extension WebexMeetingViewController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        sendChat()
        return true
    }
}

extension WebexMeetingViewController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        // Let taps on controls / the text field behave normally; only treat
        // taps elsewhere as "dismiss the keyboard".
        guard let touched = touch.view else { return true }
        if touched is UIControl { return false }
        if touched.isDescendant(of: chatInput) { return false }
        return true
    }
}

/// Top bar with the Rassd teal-to-blue gradient.
final class GradientBar: UIView {
    override class var layerClass: AnyClass { CAGradientLayer.self }

    override init(frame: CGRect) {
        super.init(frame: frame)
        guard let gradient = layer as? CAGradientLayer else { return }
        gradient.colors = [RassdTheme.secondary.cgColor, RassdTheme.primary.cgColor]
        gradient.startPoint = CGPoint(x: 0, y: 0)
        gradient.endPoint = CGPoint(x: 1, y: 1)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
}

private extension UITextField {
    func setLeftPaddingPoints(_ amount: CGFloat) {
        let padding = UIView(frame: CGRect(x: 0, y: 0, width: amount, height: frame.height))
        leftView = padding
        leftViewMode = .always
    }
}
