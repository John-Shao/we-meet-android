# App UX device acceptance matrix

Use this matrix for UI changes that static checks and JVM tests cannot fully
exercise. Test both light and dark themes unless the row says otherwise.

## Display and text

| Configuration | Required checks |
| --- | --- |
| Phone portrait, default font | Primary navigation, task, calendar, contacts, chat and meeting flows |
| Phone landscape | Meeting/call controls, QR scanner, dialogs, bottom sheets and keyboard visibility |
| Font scale 1.3x | Titles wrap without covering actions; form labels and validation remain visible |
| Font scale 1.5x | Lists remain scrollable; dialog actions remain reachable; no clipped input text |
| Font scale 2.0x | Critical actions remain reachable and expose complete TalkBack labels |
| Tablet or unfolded foldable | Content is not letterboxed; sheets and dialogs do not stretch edge-to-edge unnecessarily |
| Fold/unfold during use | Selected item, draft input, scroll position and open-detail state survive recreation |

## Insets and input

- Verify gesture and three-button navigation.
- Open the keyboard in chat, task comments, search and calendar forms.
- Confirm focused fields and submit actions remain above the keyboard.
- Rotate with dialogs and bottom sheets open; dismiss and confirm actions must
  remain reachable after state restoration.

## Meeting-specific

- Exercise voice call, video call, gallery, screen sharing and Picture-in-Picture.
- Rotate before and during a call and confirm controls remain visible.
- On a foldable, fold and unfold while connected and confirm camera rendering
  and participant state recover without rejoining.
